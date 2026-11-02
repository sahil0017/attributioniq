#!/usr/bin/env python3
"""Replay the Urban Thread input files concurrently through the running HTTP API."""

from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.request
import zipfile
from collections import deque
from concurrent.futures import FIRST_COMPLETED, Future, ThreadPoolExecutor, wait
from datetime import datetime, timezone
from pathlib import Path
from typing import IO, Any, Iterator

FILES = ("events.jsonl", "orders.csv", "ad_spend.csv")
ENDPOINTS = {
    "events.jsonl": "/collect",
    "orders.csv": "/webhooks/orders",
    "ad_spend.csv": "/ad-spend",
}
RETRYABLE_STATUS = {429, 500, 502, 503, 504}
MAX_ATTEMPTS = 4


def parse_args() -> argparse.Namespace:
    project_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--data",
        type=Path,
        nargs="+",
        default=[
            project_root / "requirement" / "urbanthread_data_sep2026.zip",
            project_root / "requirement" / "urban_thread_nov2025_oct2026.zip",
        ],
        help="one or more dataset ZIPs or extracted directories (default: both provided ZIPs)",
    )
    parser.add_argument(
        "--base-url", default=os.environ.get("ATTRIBUTION_API_URL", "http://localhost:8080"),
        help="API base URL (default: $ATTRIBUTION_API_URL or http://localhost:8080)",
    )
    parser.add_argument(
        "--header", action="append", default=[], metavar="'Name: value'",
        help="extra HTTP header for every request, e.g. an API key; repeatable",
    )
    parser.add_argument(
        "--concurrency", type=int, default=10,
        help="maximum requests in flight across all files (default: 10)",
    )
    parser.add_argument(
        "--wait", action="store_true",
        help="after sending, wait until /api/processing/status reports no pending work",
    )
    parser.add_argument(
        "--wait-timeout", type=int, default=600,
        help="seconds to wait for processing to drain with --wait (default: 600)",
    )
    return parser.parse_args()


def wait_for_processing(base_url: str, timeout: int) -> bool:
    deadline = time.monotonic() + timeout
    url = f"{base_url.rstrip('/')}/api/processing/status"
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                status = json.load(response)
            pending = (status.get("pendingSessions", 0) + status.get("pendingPeople", 0)
                       + status.get("pendingOrders", 0) + status.get("retryingJobs", 0))
            print(f"Pending recompute jobs: {pending}", file=sys.stderr)
            if pending == 0:
                return True
        except (urllib.error.URLError, TimeoutError, ValueError) as error:
            print(f"Status check failed: {error}", file=sys.stderr)
        time.sleep(2)
    return False


def open_input(source: Path, filename: str) -> IO[str]:
    if source.is_dir():
        path = source / filename
        if not path.is_file():
            raise FileNotFoundError(f"Missing input file: {path}")
        return path.open("r", encoding="utf-8-sig", newline="")

    if source.is_file() and zipfile.is_zipfile(source):
        archive = zipfile.ZipFile(source)
        try:
            member = next(
                (name for name in archive.namelist() if Path(name).name == filename), None
            )
            if member is None:
                raise FileNotFoundError(f"{filename} was not found in {source}")
            import io

            return _ZipTextFile(archive, io.TextIOWrapper(
                archive.open(member), encoding="utf-8-sig", newline=""
            ))
        except Exception:
            archive.close()
            raise

    raise FileNotFoundError(f"Data source is not an extracted directory or ZIP: {source}")


class _ZipTextFile:
    """Close both the text wrapper and the archive after streaming a ZIP member."""

    def __init__(self, archive: zipfile.ZipFile, text_file: IO[str]) -> None:
        self.archive = archive
        self.text_file = text_file

    def __enter__(self) -> IO[str]:
        return self.text_file

    def __exit__(self, *_: object) -> None:
        self.text_file.close()
        self.archive.close()


def records(source: Path, filename: str) -> Iterator[dict[str, Any]]:
    with open_input(source, filename) as stream:
        if filename.endswith(".jsonl"):
            for line_number, line in enumerate(stream, start=1):
                if line.strip():
                    try:
                        payload = json.loads(line)
                    except json.JSONDecodeError as error:
                        raise ValueError(
                            f"Invalid JSON in {filename} at line {line_number}: {error}"
                        ) from error
                    if not isinstance(payload, dict):
                        raise ValueError(f"Expected a JSON object at {filename}:{line_number}")
                    yield payload
        else:
            for line_number, payload in enumerate(csv.DictReader(stream), start=2):
                for field in ("created_at", "updated_at", "pulled_at"):
                    value = payload.get(field)
                    if value:
                        try:
                            payload[field] = normalize_utc_timestamp(value)
                        except ValueError as error:
                            raise ValueError(
                                f"Invalid {field} in {filename} at line {line_number}: {value}"
                            ) from error
                yield payload


def normalize_utc_timestamp(value: str) -> str:
    timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if timestamp.tzinfo is None:
        timestamp = timestamp.replace(tzinfo=timezone.utc)
    timestamp = timestamp.astimezone(timezone.utc)
    precision = "microseconds" if timestamp.microsecond else "seconds"
    return timestamp.isoformat(timespec=precision).replace("+00:00", "Z")


def post_record(base_url: str, endpoint: str, sequence: int,
                payload: dict[str, Any], extra_headers: dict[str, str]) -> None:
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}{endpoint}",
        data=body,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-Request-Id": f"replay-{sequence}",
            **extra_headers,
        },
        method="POST",
    )

    for attempt in range(MAX_ATTEMPTS):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                if not 200 <= response.status < 300:
                    raise RuntimeError(
                        f"Request {sequence} to {endpoint} returned HTTP {response.status}"
                    )
                response.read()
                return
        except urllib.error.HTTPError as error:
            if error.code not in RETRYABLE_STATUS or attempt == MAX_ATTEMPTS - 1:
                body_preview = error.read(500).decode("utf-8", errors="replace")
                raise RuntimeError(
                    f"Request {sequence} to {endpoint} failed with HTTP {error.code}: "
                    f"{body_preview}; payload fields: {', '.join(sorted(payload))}"
                ) from error
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt == MAX_ATTEMPTS - 1:
                raise RuntimeError(
                    f"Request {sequence} to {endpoint} failed after {MAX_ATTEMPTS} attempts: {error}"
                ) from error
        time.sleep(0.5 * (2 ** attempt))


def count_records(sources: list[Path]) -> dict[str, int]:
    return {
        filename: sum(
            sum(1 for _ in records(source, filename))
            for source in sources
        )
        for filename in FILES
    }


def records_from_sources(sources: list[Path], filename: str) -> Iterator[dict[str, Any]]:
    for source in sources:
        yield from records(source, filename)


def progress_message(totals: dict[str, int], sent: dict[str, int]) -> str:
    total = sum(totals.values())
    completed = sum(sent.values())
    width = 30
    filled = width if total == 0 else width * completed // total
    bar = f"[{'#' * filled}{'-' * (width - filled)}]"
    details = " | ".join(
        f"{filename}: {sent[filename]}/{totals[filename]}" for filename in FILES
    )
    percent = 100 if total == 0 else completed * 100 // total
    return f"{bar} {percent:3d}% ({completed}/{total}) | {details}"


def replay_files(sources: list[Path], base_url: str, concurrency: int,
                 extra_headers: dict[str, str]) -> dict[str, int]:
    totals = count_records(sources)
    streams = {
        filename: records_from_sources(sources, filename)
        for filename in FILES
    }
    pending: dict[Future[None], str] = {}
    sent = {filename: 0 for filename in FILES}
    filenames = deque(FILES)
    sequence = 0
    is_terminal = sys.stderr.isatty()
    last_reported_percent = -1
    last_rendered_at = 0.0
    print(progress_message(totals, sent), end="\r" if is_terminal else "\n",
          file=sys.stderr, flush=True)

    try:
        with ThreadPoolExecutor(max_workers=concurrency) as executor:
            while filenames or pending:
                while filenames and len(pending) < concurrency:
                    filename = filenames.popleft()
                    stream = streams[filename]
                    try:
                        payload = next(stream)
                    except StopIteration:
                        stream.close()
                        continue

                    sequence += 1
                    future = executor.submit(
                        post_record, base_url, ENDPOINTS[filename], sequence, payload,
                        extra_headers
                    )
                    pending[future] = filename
                    filenames.append(filename)

                if pending:
                    completed, _ = wait(pending, return_when=FIRST_COMPLETED)
                    for future in completed:
                        filename = pending.pop(future)
                        future.result()
                        sent[filename] += 1

                    completed_count = sum(sent.values())
                    total_count = sum(totals.values())
                    percent = 100 if total_count == 0 else completed_count * 100 // total_count
                    now = time.monotonic()
                    if (is_terminal and now - last_rendered_at >= 0.1) or (
                        not is_terminal and percent > last_reported_percent
                    ) or completed_count == total_count:
                        print(
                            progress_message(totals, sent),
                            end="\r" if is_terminal and completed_count < total_count else "\n",
                            file=sys.stderr,
                            flush=True,
                        )
                        last_reported_percent = percent
                        last_rendered_at = now
    finally:
        for stream in streams.values():
            stream.close()

    return sent


def main() -> int:
    args = parse_args()
    if args.concurrency < 1:
        print("--concurrency must be at least 1", file=sys.stderr)
        return 2

    try:
        extra_headers = dict(
            (name.strip(), value.strip())
            for name, _, value in (h.partition(":") for h in args.header)
        )
        totals = replay_files(args.data, args.base_url, args.concurrency, extra_headers)
        for filename, count in totals.items():
            print(f"Completed {filename}: {count} records", file=sys.stderr)
    except (OSError, ValueError, RuntimeError) as error:
        print(f"Replay stopped: {error}", file=sys.stderr)
        return 1
    if args.wait and not wait_for_processing(args.base_url, args.wait_timeout):
        print("Timed out waiting for processing to finish", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
