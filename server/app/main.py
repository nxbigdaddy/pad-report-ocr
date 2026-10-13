from __future__ import annotations

import base64
import json
import os
import re
import sqlite3
import uuid
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path
from typing import Any

import cv2
import httpx
import numpy as np
from fastapi import BackgroundTasks, FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from PIL import Image


APP_DIR = Path(__file__).resolve().parent
SERVER_DIR = APP_DIR.parent
DATA_DIR = SERVER_DIR / "data"
UPLOAD_DIR = DATA_DIR / "uploads"
PROCESSED_DIR = DATA_DIR / "processed"
DB_PATH = DATA_DIR / "demo.db"

MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_MB", "15")) * 1024 * 1024
TERMINAL_STATUSES = {"completed", "failed"}

app = FastAPI(title="PAD Table Recognition Demo", version="0.4.0")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def database() -> sqlite3.Connection:
    connection = sqlite3.connect(DB_PATH)
    connection.row_factory = sqlite3.Row
    return connection


def initialize_storage() -> None:
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    PROCESSED_DIR.mkdir(parents=True, exist_ok=True)
    with database() as connection:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS tasks (
                id TEXT PRIMARY KEY,
                status TEXT NOT NULL,
                original_name TEXT NOT NULL,
                raw_path TEXT NOT NULL,
                processed_path TEXT,
                result_json TEXT,
                error TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )


@app.on_event("startup")
def startup() -> None:
    initialize_storage()


def require_token(authorization: str | None) -> None:
    expected = os.getenv("DEMO_TOKEN", "").strip()
    if not expected:
        return
    if authorization != f"Bearer {expected}":
        raise HTTPException(status_code=401, detail="Invalid device token")


def update_task(
    task_id: str,
    *,
    status: str,
    processed_path: str | None = None,
    result: dict[str, Any] | None = None,
    error: str | None = None,
) -> None:
    with database() as connection:
        connection.execute(
            """
            UPDATE tasks
               SET status = ?, processed_path = COALESCE(?, processed_path),
                   result_json = ?, error = ?, updated_at = ?
             WHERE id = ?
            """,
            (
                status,
                processed_path,
                json.dumps(result, ensure_ascii=False) if result is not None else None,
                error,
                utc_now(),
                task_id,
            ),
        )


def get_task_row(task_id: str) -> sqlite3.Row:
    with database() as connection:
        row = connection.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Task not found")
    return row


def public_task(row: sqlite3.Row) -> dict[str, Any]:
    result = json.loads(row["result_json"]) if row["result_json"] else None
    payload: dict[str, Any] = {
        "task_id": row["id"],
        "status": row["status"],
        "original_name": row["original_name"],
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
        "result": result,
        "error": row["error"],
    }
    if row["processed_path"] and Path(row["processed_path"]).exists():
        payload["processed_image_url"] = f"/api/v1/tasks/{row['id']}/processed-image"
    return payload


def order_points(points: np.ndarray) -> np.ndarray:
    ordered = np.zeros((4, 2), dtype="float32")
    sums = points.sum(axis=1)
    differences = np.diff(points, axis=1).reshape(-1)
    ordered[0] = points[np.argmin(sums)]
    ordered[2] = points[np.argmax(sums)]
    ordered[1] = points[np.argmin(differences)]
    ordered[3] = points[np.argmax(differences)]
    return ordered


def clustered_positions(indices: np.ndarray, max_gap: int = 5) -> list[int]:
    if len(indices) == 0:
        return []
    groups: list[list[int]] = [[int(indices[0])]]
    for value in indices[1:]:
        value = int(value)
        if value - groups[-1][-1] <= max_gap:
            groups[-1].append(value)
        else:
            groups.append([value])
    return [int(round(sum(group) / len(group))) for group in groups]


def analyze_processed_image(image: np.ndarray, document_detected: bool) -> dict[str, Any]:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    height, width = gray.shape
    blur_score = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    brightness = float(gray.mean())
    contrast = float(gray.std())

    binary = cv2.adaptiveThreshold(
        gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 31, 13
    )
    horizontal_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (max(24, width // 18), 1))
    vertical_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (1, max(24, height // 24)))
    horizontal = cv2.morphologyEx(binary, cv2.MORPH_OPEN, horizontal_kernel)
    vertical = cv2.morphologyEx(binary, cv2.MORPH_OPEN, vertical_kernel)

    horizontal_strength = np.count_nonzero(horizontal, axis=1)
    vertical_strength = np.count_nonzero(vertical, axis=0)
    horizontal_positions = clustered_positions(np.where(horizontal_strength >= width * 0.32)[0])
    vertical_positions = clustered_positions(np.where(vertical_strength >= height * 0.32)[0])

    # Ignore implausibly dense detections caused by text strokes rather than table lines.
    table_rows = max(0, len(horizontal_positions) - 1) if len(horizontal_positions) <= 80 else 0
    table_columns = max(0, len(vertical_positions) - 1) if len(vertical_positions) <= 30 else 0
    table_detected = table_rows >= 2 and table_columns >= 2

    warnings: list[str] = []
    score = 100
    if not document_detected:
        warnings.append("未可靠定位纸张边缘，请将整张表格完整放入画面。")
        score -= 30
    if width < 600 or height < 800:
        warnings.append("表格有效分辨率偏低，建议靠近后重新拍摄。")
        score -= 18
    if blur_score < 70:
        warnings.append("图片可能模糊，请保持平板稳定并重新拍摄。")
        score -= 25
    if brightness < 65:
        warnings.append("图片过暗，请增加环境光线。")
        score -= 18
    elif brightness > 225:
        warnings.append("图片过亮或存在反光，请调整拍摄角度。")
        score -= 18
    if contrast < 28:
        warnings.append("图片对比度较低，手写内容可能难以识别。")
        score -= 12
    if not table_detected:
        warnings.append("未检测到完整表格网格，请确认表格线清晰可见。")
        score -= 15

    score = max(0, min(100, score))
    status = "good" if score >= 75 else "warning" if score >= 50 else "poor"
    return {
        "status": status,
        "score": score,
        "blur_score": round(blur_score, 1),
        "brightness": round(brightness, 1),
        "contrast": round(contrast, 1),
        "warnings": warnings,
        "table": {
            "detected": table_detected,
            "rows": table_rows,
            "columns": table_columns,
            "horizontal_lines": len(horizontal_positions),
            "vertical_lines": len(vertical_positions),
        },
    }


def perspective_correct(source_path: Path, destination_path: Path) -> dict[str, Any]:
    image = cv2.imread(str(source_path))
    if image is None:
        raise ValueError("OpenCV could not decode the uploaded image")

    original_height, original_width = image.shape[:2]
    scale = min(1.0, 1600.0 / max(original_width, original_height))
    detection = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    image_area = detection.shape[0] * detection.shape[1]
    document = None

    # Most production forms are white paper on a colored clipboard. Segmenting the
    # low-saturation bright region is more reliable than edges when a hand, clip,
    # shadows, or background objects interrupt the page border.
    hsv = cv2.cvtColor(detection, cv2.COLOR_BGR2HSV)
    close_size = max(9, int(min(detection.shape[:2]) * 0.018))
    if close_size % 2 == 0:
        close_size += 1
    # Start with a strict white threshold so bright desks and skin do not merge
    # into the paper. Relax only when the strict pass cannot find a page.
    for saturation_max, value_min in ((60, 130), (70, 120), (90, 110)):
        paper_mask = cv2.inRange(
            hsv,
            np.array([0, 0, value_min]),
            np.array([180, saturation_max, 255]),
        )
        paper_mask = cv2.morphologyEx(
            paper_mask, cv2.MORPH_CLOSE, np.ones((close_size, close_size), np.uint8)
        )
        paper_mask = cv2.morphologyEx(paper_mask, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
        paper_contours, _ = cv2.findContours(paper_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for contour in sorted(paper_contours, key=cv2.contourArea, reverse=True)[:10]:
            area_ratio = cv2.contourArea(contour) / image_area
            if not 0.18 <= area_ratio <= 0.92:
                continue
            hull = cv2.convexHull(contour)
            perimeter = cv2.arcLength(hull, True)
            for epsilon in (0.02, 0.025, 0.03, 0.035, 0.04, 0.05, 0.06, 0.07):
                polygon = cv2.approxPolyDP(hull, epsilon * perimeter, True)
                if len(polygon) == 4 and cv2.isContourConvex(polygon):
                    document = polygon.reshape(4, 2).astype("float32") / scale
                    break
            if document is not None:
                break
        if document is not None:
            break

    # Fall back to edge-based detection for paper whose color is not close to white.
    if document is None:
        gray = cv2.cvtColor(detection, cv2.COLOR_BGR2GRAY)
        gray = cv2.GaussianBlur(gray, (5, 5), 0)
        edges = cv2.Canny(gray, 50, 150)
        edges = cv2.morphologyEx(edges, cv2.MORPH_CLOSE, np.ones((7, 7), np.uint8))
        contours, _ = cv2.findContours(edges, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        for contour in sorted(contours, key=cv2.contourArea, reverse=True)[:20]:
            perimeter = cv2.arcLength(contour, True)
            polygon = cv2.approxPolyDP(contour, 0.02 * perimeter, True)
            if len(polygon) == 4 and cv2.contourArea(polygon) >= image_area * 0.18:
                document = polygon.reshape(4, 2).astype("float32") / scale
                break

    corrected = image
    detected = document is not None
    if document is not None:
        top_left, top_right, bottom_right, bottom_left = order_points(document)
        width_a = np.linalg.norm(bottom_right - bottom_left)
        width_b = np.linalg.norm(top_right - top_left)
        height_a = np.linalg.norm(top_right - bottom_right)
        height_b = np.linalg.norm(top_left - bottom_left)
        target_width = max(1, int(max(width_a, width_b)))
        target_height = max(1, int(max(height_a, height_b)))
        target = np.array(
            [[0, 0], [target_width - 1, 0], [target_width - 1, target_height - 1], [0, target_height - 1]],
            dtype="float32",
        )
        transform = cv2.getPerspectiveTransform(order_points(document), target)
        corrected = cv2.warpPerspective(image, transform, (target_width, target_height))

    # Local contrast enhancement preserves handwriting while reducing uneven lighting.
    lab = cv2.cvtColor(corrected, cv2.COLOR_BGR2LAB)
    lightness, channel_a, channel_b = cv2.split(lab)
    lightness = cv2.createCLAHE(clipLimit=1.8, tileGridSize=(8, 8)).apply(lightness)
    corrected = cv2.cvtColor(cv2.merge((lightness, channel_a, channel_b)), cv2.COLOR_LAB2BGR)
    if not cv2.imwrite(str(destination_path), corrected, [cv2.IMWRITE_JPEG_QUALITY, 92]):
        raise ValueError("Could not write the processed image")

    height, width = corrected.shape[:2]
    return {
        "document_detected": detected,
        "original_size": {"width": original_width, "height": original_height},
        "processed_size": {"width": width, "height": height},
        "quality": analyze_processed_image(corrected, detected),
    }


TABLE_PROMPT = """
你是生产日报表识别器。请识别图片中的表格，逐行转成 JSON。表头通常包括：
序号、项目号、产品名称、日期、系列、数量、完成情况、备注。

要求：
1. 不允许根据上下文虚构看不清或空白的内容；无法确认时使用 null。
2. 保留手写原文到 raw 字段，规范化值写入 value。
3. 完成情况只能是 completed、not_completed、uncertain 或 null。
4. 数量应为整数；无法确认时为 null。
5. 每行列出需要人工复核的字段名。
6. 只返回 JSON，不要 Markdown。
7. 必须严格按照竖向网格线确定单元格所属列，并在所有数据行中保持相同列对齐，不能因纸张倾斜而左右错列。
8. 日期、系列、数量是相邻三列：日期在左，系列在中，数量在右。特别注意不要把“数量”列中的整数写入“系列”。
9. 逐条检查项目号、产品名称、日期、数量和完成情况；无法辨认的字符保留 raw 并加入 uncertain_fields。
10. 仅识别表头下方的有效数据行，不要把表头或纸张外的内容作为数据行。

返回结构：
{
  "document_type": "产量日报表",
  "headers": ["序号", "项目号", "产品名称", "日期", "系列", "数量", "完成情况", "备注"],
  "rows": [
    {
      "row_number": 1,
      "project_no": {"raw": "", "value": null},
      "product_name": {"raw": "", "value": null},
      "date": {"raw": "", "value": null},
      "series": {"raw": "", "value": null},
      "quantity": {"raw": "", "value": null},
      "completion_status": {"raw": "", "value": null},
      "remark": {"raw": "", "value": null},
      "review_required": true,
      "uncertain_fields": []
    }
  ],
  "warnings": []
}
""".strip()


def parse_json_content(content: str) -> dict[str, Any]:
    cleaned = content.strip()
    cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"\s*```$", "", cleaned)
    parsed = json.loads(cleaned)
    if not isinstance(parsed, dict) or not isinstance(parsed.get("rows"), list):
        raise ValueError("Model response does not contain a rows array")
    return parsed


def recognize_with_vision_model(image_path: Path) -> dict[str, Any] | None:
    api_key = YOUR_SECRET"VISION_API_KEY", "").strip()
    model = os.getenv("VISION_MODEL", "").strip()
    if not api_key or not model:
        return None

    base_url = os.getenv("VISION_API_BASE_URL", "https://api.openai.com/v1").rstrip("/")
    image_base64 = base64.b64encode(image_path.read_bytes()).decode("ascii")
    payload = {
        "model": model,
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": "你负责从生产表格照片中忠实提取结构化数据。"},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": TABLE_PROMPT},
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/jpeg;base64,{image_base64}", "detail": "high"},
                    },
                ],
            },
        ],
    }
    with httpx.Client(timeout=httpx.Timeout(120.0, connect=20.0)) as client:
        response = client.post(
            f"{base_url}/chat/completions",
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            json=payload,
        )
        response.raise_for_status()
        body = response.json()
    content = body["choices"][0]["message"]["content"]
    return parse_json_content(content)


def process_task(task_id: str) -> None:
    try:
        row = get_task_row(task_id)
        raw_path = Path(row["raw_path"])
        processed_path = PROCESSED_DIR / f"{task_id}.jpg"
        update_task(task_id, status="processing")
        processing = perspective_correct(raw_path, processed_path)
        quality_status = processing["quality"]["status"]
        document = None if quality_status == "poor" else recognize_with_vision_model(processed_path)

        if quality_status == "poor":
            result = {
                "mode": "quality_rejected",
                "model_configured": bool(
                    os.getenv("VISION_API_KEY", "").strip() and os.getenv("VISION_MODEL", "").strip()
                ),
                "message": "图片质量未通过，已跳过大模型识别，请重新拍摄。",
                "image_processing": processing,
                "document": {
                    "document_type": "产量日报表",
                    "headers": ["序号", "项目号", "产品名称", "日期", "系列", "数量", "完成情况", "备注"],
                    "rows": [],
                    "warnings": ["图片质量未通过，未提交视觉模型。"],
                },
            }
        elif document is None:
            result = {
                "mode": "image_processing_demo",
                "model_configured": False,
                "message": "图片已上传并完成透视校正。配置 VISION_API_KEY 和 VISION_MODEL 后将自动识别表格内容。",
                "image_processing": processing,
                "document": {
                    "document_type": "产量日报表",
                    "headers": ["序号", "项目号", "产品名称", "日期", "系列", "数量", "完成情况", "备注"],
                    "rows": [],
                    "warnings": ["视觉模型尚未配置，当前未提取手写内容。"],
                },
            }
        else:
            result = {
                "mode": "vision_model",
                "model_configured": True,
                "model": os.getenv("VISION_MODEL"),
                "image_processing": processing,
                "document": document,
            }
        update_task(task_id, status="completed", processed_path=str(processed_path), result=result)
    except Exception as exc:  # The error is returned to the tablet for this demo.
        update_task(task_id, status="failed", error=f"{type(exc).__name__}: {exc}")


@app.get("/api/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "model_configured": bool(os.getenv("VISION_API_KEY", "").strip() and os.getenv("VISION_MODEL", "").strip()),
    }


@app.get("/api/v1/tasks")
def list_tasks(
    limit: int = 20,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    require_token(authorization)
    safe_limit = max(1, min(limit, 50))
    with database() as connection:
        rows = connection.execute(
            "SELECT * FROM tasks ORDER BY created_at DESC LIMIT ?", (safe_limit,)
        ).fetchall()
    return {"tasks": [public_task(row) for row in rows]}


@app.post("/api/v1/tasks", status_code=202)
async def create_task(
    background_tasks: BackgroundTasks,
    file: UploadFile = File(...),
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    require_token(authorization)
    if not file.content_type or not file.content_type.startswith("image/"):
        raise HTTPException(status_code=415, detail="Only image uploads are accepted")

    content = await file.read(MAX_UPLOAD_BYTES + 1)
    if len(content) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="Image is too large")
    if not content:
        raise HTTPException(status_code=400, detail="Uploaded image is empty")

    try:
        with Image.open(BytesIO(content)) as image:
            image.verify()
            image_format = (image.format or "JPEG").lower()
    except Exception as exc:
        raise HTTPException(status_code=400, detail="The uploaded file is not a valid image") from exc

    extension = {"jpeg": ".jpg", "png": ".png", "webp": ".webp"}.get(image_format, ".img")
    task_id = str(uuid.uuid4())
    raw_path = UPLOAD_DIR / f"{task_id}{extension}"
    raw_path.write_bytes(content)
    created_at = utc_now()
    with database() as connection:
        connection.execute(
            """
            INSERT INTO tasks(id, status, original_name, raw_path, created_at, updated_at)
            VALUES (?, 'queued', ?, ?, ?, ?)
            """,
            (task_id, file.filename or f"upload{extension}", str(raw_path), created_at, created_at),
        )
    background_tasks.add_task(process_task, task_id)
    return {"task_id": task_id, "status": "queued"}


@app.get("/api/v1/tasks/{task_id}")
def read_task(task_id: str, authorization: str | None = Header(default=None)) -> dict[str, Any]:
    require_token(authorization)
    return public_task(get_task_row(task_id))


@app.post("/api/v1/tasks/{task_id}/retry", status_code=202)
def retry_task(
    task_id: str,
    background_tasks: BackgroundTasks,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    require_token(authorization)
    row = get_task_row(task_id)
    if row["status"] not in TERMINAL_STATUSES:
        raise HTTPException(status_code=409, detail="Task is already running")
    update_task(task_id, status="queued")
    background_tasks.add_task(process_task, task_id)
    return {"task_id": task_id, "status": "queued"}


@app.get("/api/v1/tasks/{task_id}/processed-image")
def processed_image(task_id: str, authorization: str | None = Header(default=None)) -> FileResponse:
    require_token(authorization)
    row = get_task_row(task_id)
    if not row["processed_path"] or not Path(row["processed_path"]).exists():
        raise HTTPException(status_code=404, detail="Processed image is not ready")
    return FileResponse(row["processed_path"], media_type="image/jpeg")
