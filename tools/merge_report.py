#!/usr/bin/env python3
"""TimeTrace v0.1 offline report: merge Windows and Android JSONL events.

No third-party Python dependencies. Nothing leaves the machine.

Usage: python tools/merge_report.py path1.jsonl path2.jsonl --tz-offset +08:00 --date 2026-10-26
"""
import argparse
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
import json
from pathlib import Path
import re
import sys

REQUIRED = {"segment_id", "device_id", "app_id", "start_ms", "end_ms", "source", "confidence"}


def load_segments(paths):
    seen_ids = set()
    seen_intervals = set()
    result = []
    for filename in paths:
        with Path(filename).open("r", encoding="utf-8-sig") as stream:
            for line_no, line in enumerate(stream, 1):
                if not line.strip():
                    continue
                try:
                    row = json.loads(line)
                    if not isinstance(row, dict) or not REQUIRED.issubset(row):
                        raise ValueError("missing required fields")
                    start, end = row["start_ms"], row["end_ms"]
                    if type(start) is not int or type(end) is not int or not (0 <= start < end):
                        raise ValueError("invalid interval")
                    if not all(isinstance(row[k], str) and row[k] for k in
                               ("segment_id", "device_id", "app_id", "source", "confidence")):
                        raise ValueError("invalid string field")
                    identity = (row["device_id"], row["segment_id"])
                    dedup = (row["device_id"], row["app_id"], start, end)
                    if identity in seen_ids or dedup in seen_intervals:
                        continue
                    seen_ids.add(identity)
                    seen_intervals.add(dedup)
                    result.append(row)
                except (ValueError, json.JSONDecodeError) as ex:
                    print(f"Warning: skipped {filename}:{line_no}: {ex}", file=sys.stderr)
    return result


def union_ms(intervals):
    """Union duration (ms) of half-open intervals; prevent cross-device double counting."""
    merged = 0
    right = None
    for start, end in sorted(intervals):
        if right is None or start >= right:
            merged += end - start
            right = end
        elif end > right:
            merged += end - right
            right = end
    return merged


def parse_offset(value):
    if value == "Z":
        return timezone.utc
    match = re.fullmatch(r"([+-])(\d\d):(\d\d)", value)
    if not match:
        raise argparse.ArgumentTypeError("use a timezone offset such as +08:00 or Z")
    hours, minutes = int(match.group(2)), int(match.group(3))
    if hours > 14 or minutes > 59 or (hours == 14 and minutes != 0):
        raise argparse.ArgumentTypeError("invalid timezone offset")
    sign = 1 if match.group(1) == "+" else -1
    return timezone(sign * timedelta(hours=hours, minutes=minutes))


def get_report(rows, local_date=None, tz=timezone(timedelta(hours=8))):
    begin_ms = end_ms = None
    if local_date is not None:
        local_begin = datetime.combine(local_date, datetime.min.time(), tzinfo=tz)
        begin_ms = int(local_begin.timestamp() * 1000)
        end_ms = int((local_begin + timedelta(days=1)).timestamp() * 1000)
    spans = []
    per_device_spans = defaultdict(list)
    per_app_spans = defaultdict(list)
    for row in rows:
        start = max(row["start_ms"], begin_ms) if begin_ms is not None else row["start_ms"]
        end = min(row["end_ms"], end_ms) if end_ms is not None else row["end_ms"]
        if end <= start:
            continue
        spans.append((start, end))
        per_device_spans[row["device_id"]].append((start, end))
        per_app_spans[row["app_id"]].append((start, end))
    # Multiple activities from a single device must not inflate its active time.
    per_device = {name: union_ms(ranges) for name, ranges in per_device_spans.items()}
    per_app = {name: union_ms(ranges) for name, ranges in per_app_spans.items()}
    device_total_ms = sum(per_device.values())
    unique_ms = union_ms(spans)
    return {
        "segments": len(spans),
        "device_total_ms": device_total_ms,
        "unique_ms": unique_ms,
        "overlap_ms": device_total_ms - unique_ms,
        "per_device_ms": dict(sorted(per_device.items())),
        "per_app_ms": dict(sorted(per_app.items(), key=lambda pair: -pair[1])),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", help="One or more TimeTrace JSONL files")
    parser.add_argument("--date", type=date.fromisoformat, help="Local date YYYY-MM-DD")
    parser.add_argument("--tz-offset", type=parse_offset, default=timezone(timedelta(hours=8)),
                        help="Local fixed timezone offset, default +08:00 (Beijing/Singapore)")
    parser.add_argument("--json", type=Path, help="Write machine-readable aggregate report to path")
    args = parser.parse_args()
    try:
        report = get_report(load_segments(args.files), args.date, args.tz_offset)
    except OSError as ex:
        parser.error(str(ex))
    minutes = lambda ms: f"{ms / 60000:.1f} min"
    print("TimeTrace offline report")
    print(f"Segments: {report['segments']}")
    print(f"Device cumulative time: {minutes(report['device_total_ms'])}")
    print(f"At least one device active (union): {minutes(report['unique_ms'])}")
    print(f"Overlapping time: {minutes(report['overlap_ms'])}")
    print("Per device:")
    for device, ms in report["per_device_ms"].items():
        print(f"  {device}: {minutes(ms)}")
    print("Top apps:")
    for app, ms in list(report["per_app_ms"].items())[:15]:
        print(f"  {app}: {minutes(ms)}")
    if args.json:
        args.json.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"Wrote {args.json}")


if __name__ == "__main__":
    main()
