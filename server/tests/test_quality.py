from __future__ import annotations

import unittest
import uuid
from unittest.mock import patch

import cv2
import numpy as np

from server.app.main import (
    PROCESSED_DIR,
    UPLOAD_DIR,
    analyze_processed_image,
    database,
    get_task_row,
    initialize_storage,
    process_task,
    public_task,
    utc_now,
)


class ImageQualityTests(unittest.TestCase):
    def test_clear_grid_is_detected(self) -> None:
        image = np.full((1200, 800, 3), 245, dtype=np.uint8)
        for x in np.linspace(40, 760, 9, dtype=int):
            cv2.line(image, (int(x), 80), (int(x), 1120), (25, 25, 25), 3)
        for y in np.linspace(80, 1120, 25, dtype=int):
            cv2.line(image, (40, int(y)), (760, int(y)), (25, 25, 25), 3)

        quality = analyze_processed_image(image, document_detected=True)

        self.assertTrue(quality["table"]["detected"])
        self.assertEqual(quality["table"]["columns"], 8)
        self.assertEqual(quality["table"]["rows"], 24)
        self.assertIn(quality["status"], {"good", "warning"})

    def test_blank_low_resolution_image_requires_retake(self) -> None:
        image = np.full((360, 420, 3), 240, dtype=np.uint8)

        quality = analyze_processed_image(image, document_detected=False)

        self.assertEqual(quality["status"], "poor")
        self.assertFalse(quality["table"]["detected"])
        self.assertGreaterEqual(len(quality["warnings"]), 3)

    def test_poor_image_is_rejected_before_model_call(self) -> None:
        initialize_storage()
        task_id = f"test-{uuid.uuid4()}"
        raw_path = UPLOAD_DIR / f"{task_id}.jpg"
        processed_path = PROCESSED_DIR / f"{task_id}.jpg"
        cv2.imwrite(str(raw_path), np.full((360, 420, 3), 240, dtype=np.uint8))
        created_at = utc_now()
        with database() as connection:
            connection.execute(
                """
                INSERT INTO tasks(id, status, original_name, raw_path, created_at, updated_at)
                VALUES (?, 'queued', ?, ?, ?, ?)
                """,
                (task_id, "poor-test.jpg", str(raw_path), created_at, created_at),
            )

        try:
            with patch(
                "server.app.main.recognize_with_vision_model",
                side_effect=AssertionError("poor image must not reach the model"),
            ) as model_call:
                process_task(task_id)

            task = public_task(get_task_row(task_id))
            self.assertEqual(task["status"], "completed")
            self.assertEqual(task["result"]["mode"], "quality_rejected")
            self.assertEqual(task["result"]["document"]["rows"], [])
            model_call.assert_not_called()
        finally:
            with database() as connection:
                connection.execute("DELETE FROM tasks WHERE id = ?", (task_id,))
            raw_path.unlink(missing_ok=True)
            processed_path.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
