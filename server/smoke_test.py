from __future__ import annotations

import sys
import time
from pathlib import Path

import httpx


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: python smoke_test.py <image-path>")
        return 2
    image_path = Path(sys.argv[1])
    with image_path.open("rb") as source:
        response = httpx.post(
            "http://127.0.0.1:8000/api/v1/tasks",
            files={"file": (image_path.name, source, "image/jpeg")},
            timeout=30,
        )
    response.raise_for_status()
    task_id = response.json()["task_id"]
    for _ in range(60):
        task = httpx.get(f"http://127.0.0.1:8000/api/v1/tasks/{task_id}", timeout=10).json()
        if task["status"] in {"completed", "failed"}:
            print(task)
            return 0 if task["status"] == "completed" else 1
        time.sleep(0.5)
    print("Timed out waiting for the task")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

