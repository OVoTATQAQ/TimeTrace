import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / "tools" / "merge_report.py"
spec = importlib.util.spec_from_file_location("merge_report", MODULE)
merger = importlib.util.module_from_spec(spec)
spec.loader.exec_module(merger)


def event(ident, device, app, start, end):
    return {"segment_id": ident, "device_id": device, "app_id": app,
            "start_ms": start, "end_ms": end,
            "source": "windows_foreground_poll", "confidence": "observed"}


class ReportTests(unittest.TestCase):
    def test_overlap_and_device_sums(self):
        rows = [event("1", "pc", "windows:code.exe", 0, 60_000),
                event("2", "phone", "android:demo", 30_000, 90_000)]
        report = merger.get_report(rows)
        self.assertEqual(report["device_total_ms"], 120_000)
        self.assertEqual(report["unique_ms"], 90_000)
        self.assertEqual(report["overlap_ms"], 30_000)

    def test_adjacent_union(self):
        self.assertEqual(merger.union_ms([(0, 100), (100, 300), (80, 150)]), 300)

    def test_repeated_import_is_idempotent(self):
        rows = [event("1", "pc", "windows:code.exe", 0, 100)]
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / "rows.jsonl"
            file.write_text("\n".join(json.dumps(row) for row in rows + rows), encoding="utf-8")
            self.assertEqual(len(merger.load_segments([file, file])), 1)

    def test_invalid_intervals_skipped(self):
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / "rows.jsonl"
            file.write_text(json.dumps(event("bad", "pc", "windows:code.exe", 100, 30)), encoding="utf-8")
            self.assertEqual(merger.load_segments([file]), [])

    def test_date_clipping(self):
        from datetime import date, timedelta, timezone
        tz = timezone(timedelta(hours=8))
        from datetime import datetime
        midnight = int(datetime(2026, 10, 26, tzinfo=tz).timestamp() * 1000)
        rows = [event("1", "pc", "windows:test", midnight - 60_000, midnight + 120_000)]
        report = merger.get_report(rows, date(2026, 10, 26), tz)
        self.assertEqual(report["unique_ms"], 120_000)


if __name__ == "__main__":
    unittest.main()

# A single device can have overlapping lifecycle intervals; these must not
# inflate its active time, independently of multi-device overlap.
class SameDeviceTests(unittest.TestCase):
    def test_same_device_overlap_not_double_counted(self):
        rows = [event("a", "pc", "windows:code.exe", 0, 60_000),
                event("b", "pc", "windows:code.exe", 30_000, 90_000)]
        report = merger.get_report(rows)
        self.assertEqual(report["device_total_ms"], 90_000)
        self.assertEqual(report["unique_ms"], 90_000)
        self.assertEqual(report["overlap_ms"], 0)
