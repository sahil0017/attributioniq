#!/usr/bin/env python3
"""Run a bounded synthetic HTTP-ingestion benchmark against a local AttributionIQ API."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from typing import Any

PROCESSING_PATH = "/api/processing/status"
COLLECT_PATH = "/collect"
QUEUE_FIELDS = ("pendingSessions", "pendingPeople", "pendingOrders")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument(
        "--requests", type=int, default=250,
        help="synthetic event count for each concurrency level (default: 250)",
    )
    parser.add_argument(
        "--concurrency", type=int, nargs="+", default=[1, 5, 10, 20],
        help="one or more concurrent request levels (default: 1 5 10 20)",
    )
    parser.add_argument(
        "--queue-timeout", type=int, default=180,
        help="seconds to wait for the recompute queue to drain between runs",
    )
    return parser.parse_args()


def get_json(url: str) -> dict[str, Any]:
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=5) as response:
        body = json.loads(response.read())
    if not isinstance(body, dict):
        raise RuntimeError(f"Expected a JSON object from {url}")
    return body


def queue_depth(status: dict[str, Any]) -> int:
    return sum(int(status.get(field, 0)) for field in QUEUE_FIELDS)


def wait_for_empty_queue(base_url: str, timeout: int) -> float:
    started = time.monotonic()
    deadline = started + timeout
    while time.monotonic() < deadline:
        if queue_depth(get_json(f"{base_url.rstrip('/')}{PROCESSING_PATH}")) == 0:
            return time.monotonic() - started
        time.sleep(0.5)
    raise TimeoutError(f"Recompute queue did not drain within {timeout}s")


def send_event(base_url: str, event_id: str, payload: dict[str, Any]) -> float:
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}{COLLECT_PATH}",
        data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-Request-Id": event_id,
        },
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()
            if not 200 <= response.status < 300:
                raise RuntimeError(f"HTTP {response.status} for {event_id}")
    except urllib.error.HTTPError as error:
        detail = error.read(300).decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {error.code} for {event_id}: {detail}") from error
    return time.perf_counter() - started


def percentile(values: list[float], percentage: int) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(percentage * len(ordered) / 100) - 1)]


def run_profile(base_url: str, count: int, concurrency: int, run_id: str,
                queue_timeout: int) -> dict[str, float]:
    started = time.perf_counter()
    latencies: list[float] = []
    now = datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")

    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = []
        for index in range(count):
            event_id = f"bench-{run_id}-{concurrency}-{index}"
            payload = {
                "event_id": event_id,
                "event_name": "benchmark_event",
                "timestamp": now,
                "received_at": now,
                "anonymous_id": f"bench-visitor-{run_id}-{concurrency}-{index}",
                "utm_source": "benchmark",
            }
            futures.append(executor.submit(send_event, base_url, event_id, payload))
        for future in concurrent.futures.as_completed(futures):
            latencies.append(future.result())

    elapsed = time.perf_counter() - started
    queue_drain = wait_for_empty_queue(base_url, timeout=queue_timeout)
    return {
        "elapsed": elapsed,
        "throughput": count / elapsed,
        "p50_ms": percentile(latencies, 50) * 1000,
        "p95_ms": percentile(latencies, 95) * 1000,
        "p99_ms": percentile(latencies, 99) * 1000,
        "queue_drain": queue_drain,
    }


def main() -> int:
    args = parse_args()
    if args.requests < 1 or args.requests > 10_000:
        print("--requests must be between 1 and 10000 per concurrency level", file=sys.stderr)
        return 2
    if args.queue_timeout < 1 or any(level < 1 or level > 100 for level in args.concurrency):
        print("Concurrency must be 1–100 and queue timeout must be positive", file=sys.stderr)
        return 2

    base_url = args.base_url.rstrip("/")
    run_id = uuid.uuid4().hex[:12]
    try:
        initial_status = get_json(f"{base_url}{PROCESSING_PATH}")
        if queue_depth(initial_status) != 0:
            raise RuntimeError(
                "Start with an empty recompute queue for a comparable run; "
                f"current status: {initial_status}"
            )

        print(f"Run ID: {run_id} | events/profile: {args.requests}")
        print("Concurrency | req/s | p50 ms | p95 ms | p99 ms | queue drain s")
        for concurrency in args.concurrency:
            result = run_profile(base_url, args.requests, concurrency, run_id, args.queue_timeout)
            print(
                f"{concurrency:11d} | {result['throughput']:5.1f} | "
                f"{result['p50_ms']:6.1f} | {result['p95_ms']:6.1f} | "
                f"{result['p99_ms']:6.1f} | {result['queue_drain']:13.1f}",
                flush=True,
            )
    except (OSError, ValueError, RuntimeError, TimeoutError) as error:
        print(f"Benchmark failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
