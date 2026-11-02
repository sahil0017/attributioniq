#!/usr/bin/env python3
"""Standalone sender: read a dataset ZIP on this machine and POST it to a deployed API.

The ZIP must contain events.jsonl, orders.csv and ad_spend.csv (any folder depth).
Uses only the Python 3 standard library, so it can be copied to any machine.

Only the base URL is needed; endpoint paths are built in
(/collect, /webhooks/orders, /ad-spend).

Example:
  python3 ingest_dataset.py /data/urbanthread_data_sep2026.zip \
      --api-url https://attribution.example.com --wait
"""

from __future__ import annotations

import argparse
import csv
import io
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
from typing import Any, Iterator

# file name inside the ZIP -> default endpoint path
DATASETS = {
    "events.jsonl": "/collect",
    "orders.csv": "/webhooks/orders",
    "ad_spend.csv": "/ad-spend",
}
STATUS_PATH = "/api/processing/status"
RETRYABLE = {429, 500, 502, 503, 504}
MAX_ATTEMPTS = 4


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("zip_files", nargs="+", type=Path, help="dataset ZIP file(s) on this machine")
    p.add_argument("--api-url", default=os.environ.get("ATTRIBUTION_API_URL"),
                   help="backend base URL (or env ATTRIBUTION_API_URL)")
    p.add_argument("--api-key", default=os.environ.get("ATTRIBUTION_API_KEY"),
                   help="sent as 'X-Api-Key' (or env ATTRIBUTION_API_KEY)")
    p.add_argument("--header", action="append", default=[], metavar="'Name: value'",
                   help="extra header for every request; repeatable")
    p.add_argument("--only", nargs="+", choices=list(DATASETS), default=list(DATASETS),
                   help="send only these files (default: all three)")
    p.add_argument("--concurrency", type=int, default=10, help="parallel requests (default 10)")
    p.add_argument("--timeout", type=int, default=30, help="per-request timeout seconds")
    p.add_argument("--wait", action="store_true",
                   help="after sending, wait until the API reports no pending processing")
    p.add_argument("--wait-timeout", type=int, default=900)
    p.add_argument("--continue-on-error", action="store_true",
                   help="log failed records and keep going (default: stop at first failure)")
    p.add_argument("--dry-run", action="store_true", help="parse and count only; send nothing")
    return p.parse_args()


def normalize_ts(value: str) -> str:
    ts = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if ts.tzinfo is None:
        ts = ts.replace(tzinfo=timezone.utc)
    ts = ts.astimezone(timezone.utc)
    return ts.isoformat(timespec="microseconds" if ts.microsecond else "seconds").replace(
        "+00:00", "Z")


def read_records(zip_path: Path, name: str) -> Iterator[dict[str, Any]]:
    with zipfile.ZipFile(zip_path) as archive:
        member = next((m for m in archive.namelist()
                       if Path(m).name == name and not m.startswith("__MACOSX")), None)
        if member is None:
            raise FileNotFoundError(f"{name} not found in {zip_path}")
        with io.TextIOWrapper(archive.open(member), encoding="utf-8-sig", newline="") as fh:
            if name.endswith(".jsonl"):
                for n, line in enumerate(fh, 1):
                    if line.strip():
                        try:
                            yield json.loads(line)
                        except json.JSONDecodeError as e:
                            raise ValueError(f"{zip_path}:{name}:{n} invalid JSON: {e}") from e
            else:
                for n, row in enumerate(csv.DictReader(fh), 2):
                    for field in ("created_at", "updated_at", "pulled_at"):
                        if row.get(field):
                            try:
                                row[field] = normalize_ts(row[field])
                            except ValueError as e:
                                raise ValueError(
                                    f"{zip_path}:{name}:{n} bad {field}: {row[field]}") from e
                    yield row


def post(url: str, payload: dict[str, Any], headers: dict[str, str], timeout: int,
         request_id: str) -> None:
    req = urllib.request.Request(
        url, data=json.dumps(payload, separators=(",", ":")).encode(), method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json",
                 "X-Request-Id": request_id, **headers})
    for attempt in range(MAX_ATTEMPTS):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                resp.read()
                return
        except urllib.error.HTTPError as e:
            if e.code not in RETRYABLE or attempt == MAX_ATTEMPTS - 1:
                raise RuntimeError(f"HTTP {e.code} from {url}: "
                                   f"{e.read(300).decode(errors='replace')}") from e
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt == MAX_ATTEMPTS - 1:
                raise RuntimeError(f"{url} unreachable after {MAX_ATTEMPTS} attempts: {e}") from e
        time.sleep(0.5 * 2 ** attempt)


def progress_line(totals: dict[str, int], sent: dict[str, int], failed: int) -> str:
    total, done = sum(totals.values()), sum(sent.values())
    width = 30
    filled = width if total == 0 else width * done // total
    pct = 100 if total == 0 else done * 100 // total
    details = " | ".join(f"{n}: {sent[n]}/{totals[n]}" for n in totals)
    extra = f" | failed: {failed}" if failed else ""
    return f"[{'#' * filled}{'-' * (width - filled)}] {pct:3d}% ({done}/{total}) | {details}{extra}"


def send_all(zips: list[Path], names: list[str], base: str, args: argparse.Namespace,
             headers: dict[str, str]) -> tuple[dict[str, int], int]:
    """Round-robin events, orders and ad spend across one shared concurrency limit."""
    totals = {n: sum(1 for z in zips for _ in read_records(z, n)) for n in names}
    sent = {n: 0 for n in names}
    failed = 0

    def stream(name: str) -> Iterator[dict[str, Any]]:
        for z in zips:
            yield from read_records(z, name)

    streams = {n: stream(n) for n in names}
    order = deque(names)
    pending: dict[Future, str] = {}
    seq = 0
    tty = sys.stderr.isatty()
    last_pct, last_at = -1, 0.0
    print(progress_line(totals, sent, failed), end="\r" if tty else "\n",
          file=sys.stderr, flush=True)

    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        try:
            while order or pending:
                while order and len(pending) < args.concurrency:
                    name = order.popleft()
                    try:
                        record = next(streams[name])
                    except StopIteration:
                        continue
                    seq += 1
                    fut = pool.submit(post, base + DATASETS[name], record, headers,
                                      args.timeout, f"send-{seq}")
                    pending[fut] = name
                    order.append(name)
                if not pending:
                    continue
                done, _ = wait(pending, return_when=FIRST_COMPLETED)
                for fut in done:
                    name = pending.pop(fut)
                    try:
                        fut.result()
                        sent[name] += 1
                    except RuntimeError as e:
                        if not args.continue_on_error:
                            raise
                        failed += 1
                        print(f"\n  failed: {e}", file=sys.stderr)
                completed = sum(sent.values())
                pct = 100 if sum(totals.values()) == 0 else completed * 100 // sum(totals.values())
                now = time.monotonic()
                finished = completed + failed == sum(totals.values())
                if (tty and now - last_at >= 0.1) or (not tty and pct > last_pct) or finished:
                    print(progress_line(totals, sent, failed),
                          end="\r" if tty and not finished else "\n", file=sys.stderr, flush=True)
                    last_pct, last_at = pct, now
        except BaseException:
            for fut in pending:
                fut.cancel()
            print(file=sys.stderr)
            raise
    return sent, failed


def wait_until_processed(base: str, headers: dict[str, str], timeout: int) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            req = urllib.request.Request(base + STATUS_PATH, headers=headers)
            with urllib.request.urlopen(req, timeout=30) as resp:
                s = json.load(resp)
            pending = sum(s.get(k, 0) for k in
                          ("pendingSessions", "pendingPeople", "pendingOrders", "retryingJobs"))
            print(f"Pending processing jobs: {pending}", file=sys.stderr)
            if pending == 0:
                return True
        except (urllib.error.URLError, TimeoutError, ValueError) as e:
            print(f"Status check failed: {e}", file=sys.stderr)
        time.sleep(3)
    return False


def main() -> int:
    args = parse_args()
    if not args.api_url:
        print("--api-url (or ATTRIBUTION_API_URL) is required", file=sys.stderr)
        return 2
    if args.concurrency < 1:
        print("--concurrency must be >= 1", file=sys.stderr)
        return 2
    for z in args.zip_files:
        if not zipfile.is_zipfile(z):
            print(f"Not a ZIP file: {z}", file=sys.stderr)
            return 2

    base = args.api_url.rstrip("/")
    headers = {k.strip(): v.strip() for k, _, v in (h.partition(":") for h in args.header)}
    if args.api_key:
        headers["X-Api-Key"] = args.api_key

    try:
        if args.dry_run:
            for zip_path in args.zip_files:
                for name in args.only:
                    count = sum(1 for _ in read_records(zip_path, name))
                    print(f"[dry-run] {zip_path.name}:{name} -> {base + DATASETS[name]}: "
                          f"{count} records")
            return 0
        sent, total_failed = send_all(args.zip_files, args.only, base, args, headers)
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as e:
        print(f"Stopped: {e}", file=sys.stderr)
        return 1

    for name, count in sent.items():
        print(f"Completed {name}: {count} records", file=sys.stderr)
    if args.wait and not wait_until_processed(base, headers, args.wait_timeout):
        print("Timed out waiting for processing to finish", file=sys.stderr)
        return 1
    return 1 if total_failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
