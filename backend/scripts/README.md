# Scripts

| Script | Purpose |
|--------|---------|
| `ingest_dataset.py` | Standalone sender: reads a dataset ZIP on this machine and POSTs it to a running backend |
| `replay_attribution_data.py` | Same idea, for use inside this repo (defaults to the ZIPs in `requirement/`) |
| `benchmark_ingestion.py` | Synthetic ingestion benchmark |
| `prestart.sh` | Local start-up helper (Colima/Docker, PostgreSQL, app) |

## `ingest_dataset.py`

Python 3 standard library only, so the single file can be copied to any
machine. It needs the **base URL** of the backend; endpoint paths are built in.

### Quick start

```bash
python3 ingest_dataset.py urbanthread_data_sep2026.zip \
  --api-url http://localhost:8080 --wait
```

Several ZIPs can be given; each file type is processed in the order given:

```bash
python3 ingest_dataset.py sep2026.zip nov2025_oct2026.zip --api-url https://your-api-host --wait
```

Events, orders and ad spend are sent interleaved under one concurrency limit,
with a single progress bar:

```
[##############------------------]  46% (1560/3383) | events.jsonl: 900/2000 | orders.csv: 520/1153 | ad_spend.csv: 140/230
```

### Input

A ZIP containing these three files (at any folder depth; `__MACOSX` entries are
ignored). A file may be absent from the archive only if you exclude it with
`--only`.

| File | Sent to | Format |
|------|---------|--------|
| `events.jsonl` | `POST /collect` | JSON Lines, one event object per line |
| `orders.csv` | `POST /webhooks/orders` | CSV with header row |
| `ad_spend.csv` | `POST /ad-spend` | CSV with header row |

Files are streamed from the ZIP; no need to unzip. UTF-8 (with or without BOM).

**`events.jsonl`** — one JSON object per line. Required: `event_id`,
`event_name`, `anonymous_id`. Common fields:

```json
{"event_id": "332e55dc91fc44a5", "event_name": "page_view",
 "ts": "2026-09-01T00:14:41.895Z", "received_at": "2026-09-01T00:14:45.622Z",
 "anonymous_id": "anon_c7c7508f49",
 "page_url": "https://www.urbanthread.in/?utm_source=google&gclid=...",
 "referrer": "https://www.google.com/", "user_agent": "...", "ip": "49.32.151.36"}
```

Optional: `email_hash`, `value`, `currency`, `order_id` (purchase events),
`utm_*`, `click_id_type`/`click_id`, `is_bot`, `is_test`. Events are
idempotent by `event_id`. Records are sent as-is; the API validates them.

**`orders.csv`**

```csv
order_id,email_hash,created_at,updated_at,total,currency,financial_status
#100839,ff7bf5...,2026-09-01 01:07:01,2026-09-01 01:07:01,23.79,USD,paid
```

`order_id`, `created_at`, `total`, `currency` and `financial_status` are
required. Multiple rows per `order_id` represent webhook versions; the newest
`updated_at` wins.

**`ad_spend.csv`**

```csv
date,platform,campaign,spend,currency,pulled_at
2026-09-01,meta,meta_retargeting_sep,7008.92,INR,2026-09-01 23:11:00
```

`platform` is `meta`, `google` or `tiktok`. Re-pulled snapshots (same
date/platform/campaign, newer `pulled_at`) supersede older ones.

**Timestamps.** In the CSV files `created_at`, `updated_at` and `pulled_at` are
normalised to UTC ISO-8601 (`2026-09-01 01:07:01` becomes
`2026-09-01T01:07:01Z`); values without a timezone are treated as UTC.
Invalid timestamps stop the run with the file and line number.

### Options

| Option | Default | Purpose |
|--------|---------|---------|
| `zip_files...` | required | One or more dataset ZIPs |
| `--api-url` / `ATTRIBUTION_API_URL` | required | Backend base URL, e.g. `https://host` |
| `--api-key` / `ATTRIBUTION_API_KEY` | none | Sent as `X-Api-Key` (for a protected API) |
| `--header 'Name: value'` | none | Extra header on every request; repeatable |
| `--only FILE...` | all three | Send a subset: `events.jsonl`, `orders.csv`, `ad_spend.csv` |
| `--concurrency N` | `10` | Requests in flight across all files |
| `--timeout S` | `30` | Per-request timeout in seconds |
| `--wait` | off | After sending, poll `/api/processing/status` until no work is pending |
| `--wait-timeout S` | `900` | Limit for `--wait` |
| `--continue-on-error` | off | Log failed records and continue instead of stopping |
| `--dry-run` | off | Parse and count records; send nothing |

### Behaviour

- **Retries:** HTTP 429/500/502/503/504 and timeouts are retried 4 times with
  exponential backoff. Other 4xx responses are not retried.
- **Failure handling:** by default the run stops at the first failed record
  (exit code 1, with the HTTP status and response body). With
  `--continue-on-error` failures are counted and reported, and the exit code is
  still 1 if any failed.
- **Idempotent / re-runnable:** repeating a run is safe (events deduplicate by
  `event_id`, older order webhooks are ignored, spend snapshots are
  append-only). Use this to resume after an interruption.
- **Processing is asynchronous:** the API returns before sessions and
  attribution are recomputed. `--wait` blocks until
  `pendingSessions + pendingPeople + pendingOrders + retryingJobs` is 0.
- **Memory:** records are streamed; only a bounded number are in flight.
- **Start-up cost:** records are counted once before sending so the progress bar
  has a total, which takes a few seconds on large files.

### Exit codes

| Code | Meaning |
|------|---------|
| `0` | All records sent (and processing finished, with `--wait`) |
| `1` | A record failed, input was invalid, or `--wait` timed out |
| `2` | Bad arguments (missing `--api-url`, not a ZIP, invalid `--concurrency`) |

### Verify afterwards

Run Part B of [`../queries.sql`](../queries.sql) against the database:

```bash
sed -n '/^-- PART B — REVIEWER QUERIES/,$p' ../queries.sql | psql -h localhost -U attribution -d attributioniq
```
