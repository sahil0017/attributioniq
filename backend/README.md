# AttributionIQ

AttributionIQ is a first-party attribution pipeline for collecting website events,
order webhooks, and ad-spend snapshots; linking customer journeys; and reporting
channel revenue, spend, and ROAS.

## Technology stack

- Java 21+ and Spring Boot 4
- PostgreSQL 17
- Flyway database migrations
- Python 3 for the ZIP replay and optional Streamlit dashboard
- Docker Compose for local PostgreSQL

## Run locally

### Prerequisites

- JDK 21 or later
- Docker Engine with the Compose plugin
- Python 3 only if running the replay script or dashboard

### Start PostgreSQL and the backend

The supplied Compose file starts **PostgreSQL only**. The Spring Boot app runs
locally through Maven:

```bash
docker compose up -d --wait postgres
./mvnw spring-boot:run
```

Flyway applies pending migrations automatically when the app starts. Check that
the API is healthy:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/processing/status
```

Stop the database container while preserving its named volume:

```bash
docker compose down
```

To delete the local database and all stored data, explicitly remove the volume:

```bash
docker compose down --volumes
```

The database credentials in `compose.yaml` and the Maven defaults are for local
development only. Configure `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and
`PGPASSWORD` for the application. The standalone Flyway Maven plugin uses
`PGURL`, `PGUSER`, and `PGPASSWORD` (and defaults to the local Compose database).
Do not use the development credentials in a shared or production environment.

### Run tests and build

```bash
./mvnw test
./mvnw package
```

### Start the Streamlit dashboard

With the backend running, use a virtual environment to avoid modifying the
system Python installation:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
streamlit run streamlit_app.py
```

The dashboard defaults to `http://localhost:8080`, refreshes every five seconds,
and shows latest-ingestion time/source and recomputation backlog. Set
`ATTRIBUTION_API_URL` if the backend uses another base URL.

## High-level architecture

```text
Website tracking pixel ── POST /collect ─────┐
Store order system ────── POST /webhooks/orders ──> Spring Boot API
Ad platform imports ───── POST /ad-spend ────┘          │
                                                        ├── Validate, normalize,
                                                        │   deduplicate and persist
                                                        ▼
                                                   PostgreSQL
                                             raw facts + durable queues
                                                        │
                                         scheduled recompute worker
                                  sessions → identities → order attribution
                                                        │
                                                        ▼
                          GET /api/reports/channels ─ reporting SQL
                          GET /api/processing/status ─ dashboard freshness
```

Ingestion endpoints persist raw data and durable recompute jobs transactionally.
The worker sessionizes events, resolves identities, and computes first- and
last-click attribution. PostgreSQL `SKIP LOCKED` lets multiple application
instances claim different jobs safely; failed jobs are retried with backoff.
The dashboard reads reports from the API and refreshes independently from
ingestion, showing when queued work means figures may still be catching up.

## Developer guide: classes and methods

Code is organized by responsibility under
`src/main/java/com/attributioniq/attribution_iq`:

| Class | Responsibility and key methods |
|---|---|
| `api/IngestionController` | HTTP boundary. `collect`, `orderWebhook`, and `adSpend` validate request DTOs, call the pipeline service, and return 202 for accepted records or 200 for duplicates. |
| `service/AttributionPipelineService` | Ingestion business logic. `collect` validates timestamps and campaign fields, deduplicates events, links identity, and enqueues session work. `receiveOrder` records webhook history/current state and queues attribution work. `receiveSpend` stores a spend snapshot. Methods are transactional so facts and corresponding jobs commit together. |
| `service/AttributionRecomputeWorker` | Scheduled queue drainer. `processPendingJobs` rotates through session, person, and order queues up to the configured per-cycle budget; failures are scheduled for retry. |
| `service/AttributionRecomputeProcessor` | Transactional units of derived work. `processNextSessionJob` rebuilds a visitor's sessions; `processNextPersonJob` recomputes attribution for a linked person's orders; `processNextOrderJob` handles an order that did not yet have a person link. |
| `database/AttributionRepository` | SQL persistence for raw inputs, identity merges, sessions, attribution, and queues. `linkIdentity` merges anonymous/email identities; `findSessionEvents` reads eligible events in event-time order; `findTouches` selects sessions inside an order's attribution window; `saveAttribution` upserts the result. |
| `api/ReportController` | HTTP endpoints for channel reports, daily reports, and available report dates. |
| `service/ReportService` | Validates report date ranges, maps daily database results, and aggregates channel totals and ROAS. |
| `database/ReportRepository` | Calls PostgreSQL `channel_daily(...)` and looks up reporting date bounds. |
| `api/ProcessingStatusController` | Exposes pending/retrying queue counts and latest ingestion time/source for the dashboard. |
| `database/RejectedRequestRepository` | Persists validation and request rejection metadata. |
| `config/OpenApiConfig` | Names and describes the generated OpenAPI specification. |

### Request and recompute flow

1. `IngestionController` validates the request shape and delegates to
   `AttributionPipelineService`.
2. The service normalizes source data and performs idempotent writes using
   `AttributionRepository`. For events and orders, the raw write and enqueue are
   in one database transaction; duplicates do not create redundant work.
3. `AttributionRecomputeWorker.processPendingJobs()` invokes one processor method
   per available job. Repository claim methods use `FOR UPDATE SKIP LOCKED` so
   multiple app instances do not claim the same row.
4. The processor rebuilds a visitor's sessions, then refreshes relevant order
   attribution. Paid, non-test orders count as revenue. First- and last-click
   results are stored independently in `order_attribution`.
5. `ReportController` delegates to `ReportService` and `ReportRepository`; SQL
   combines attributed revenue with the latest available ad-spend snapshots.
   The processing-status endpoint helps clients distinguish a current report
   from one still catching up.

Request/response DTOs live in `api/dto`; closed sets such as attribution model,
financial status, and ad platform live in `domain`. Database schema invariants,
seed data, SQL functions, and views live in the Flyway migration scripts.

Public service and repository methods have short JavaDoc summaries next to their
declarations. Start with the table above for the high-level map, then follow the
controller-to-service-to-repository calls for the behavior you need to change.

## API

| Method and path | Purpose |
|---|---|
| `POST /collect` | Accept one website event |
| `POST /webhooks/orders` | Accept an order update |
| `POST /ad-spend` | Accept an ad-platform spend snapshot |
| `GET /api/reports/channels?from=YYYY-MM-DD&to=YYYY-MM-DD&model=last_click` | Channel and daily report |
| `GET /api/reports/data-range` | Available report dates |
| `GET /api/processing/status` | Pending/retrying recompute jobs and latest ingestion time/source |
| `GET /actuator/health` | Application health |

Interactive API documentation is available at
`http://localhost:8080/swagger-ui/index.html`; the generated OpenAPI document is
at `http://localhost:8080/v3/api-docs`.

Dates in reports are inclusive UTC calendar dates, revenue and spend are
converted to INR, and ROAS is revenue divided by spend. The attribution window
is seven days; session boundaries use a 30-minute inactivity gap. Both are
configurable in the database `settings` table.

## Database schema

The authoritative schema is in `src/main/resources/db/migration`. Main tables:

| Table | Role |
|---|---|
| `events` | Idempotent raw website events keyed by `event_id` |
| `order_webhooks` | Append-only order webhook versions |
| `orders` | Latest accepted state for each order |
| `ad_spend_pulls` | Append-only ad-spend snapshots |
| `rejects` | Invalid requests and rejection metadata |
| `fx_rates`, `settings`, `channel_rules` | Reference data and pipeline policy |
| `persons`, `person_identifiers` | Canonical identities and anonymous/email identifiers |
| `sessions` | Derived visitor sessions linked to source events |
| `order_attribution` | First- and last-click attribution per order |
| `session_recompute_queue` | Durable session rebuild jobs by anonymous visitor |
| `person_recompute_queue` | Durable attribution jobs by canonical person |
| `order_recompute_queue` | Durable attribution jobs for not-yet-linked orders |

Reporting objects:

- `classify_touch(...)` applies channel rules to a marketing touch.
- `ad_spend_current` selects the latest spend snapshot per date/platform/campaign
  and converts it to INR.
- `channel_daily(from, to, model)` returns inclusive daily channel metrics,
  orders, revenue, spend, and ROAS. Revenue filtering uses a half-open UTC
  timestamp range so PostgreSQL can use the existing `order_at` index; avoid
  wrapping `order_at` in a date conversion in the filter.

The recompute queues are expected to be mostly empty when the worker is caught
up. `GET /api/processing/status` exposes their current counts and retries.

## Flyway migrations

Spring Boot runs Flyway at startup. Migrations are versioned, ordered SQL files
and should be added for schema changes; do not edit an already-applied migration
in a shared database.

| Migration | Change |
|---|---|
| `V1__initial_schema.sql` | Initial schema bootstrap |
| `V2__init_attr_schema.sql` | Attribution tables, indexes, seed rules, functions, and views |
| `V3__remove_unused_schema_check.sql` | Remove bootstrap-only table |
| `V4__durable_recompute_jobs.sql` | Add job retry metadata/indexes and order queue |
| `V5__optimize_channel_daily_date_filter.sql` | Make the report date predicate index-friendly |

Inspect or apply migrations manually when needed:

```bash
./mvnw flyway:info
./mvnw flyway:migrate
```

Use the same database URL and credentials for the app and Flyway. Flyway tracks
applied versions in `flyway_schema_history`.

## Send a ZIP to a deployed API (standalone script)

`scripts/ingest_dataset.py` is a self-contained sender (Python 3 standard
library only; copy it to any machine). Give it the ZIP on disk and the backend
base URL only (endpoint paths are built in); it reads `events.jsonl`, `orders.csv` and `ad_spend.csv` straight from the
ZIP and POSTs them to `/collect`, `/webhooks/orders` and `/ad-spend`.

```bash
# check what would be sent, without sending
python3 scripts/ingest_dataset.py /data/urbanthread_data_sep2026.zip \
  --api-url https://attribution.example.com --dry-run

# send and wait until the backend has finished processing
python3 scripts/ingest_dataset.py /data/urbanthread_data_sep2026.zip \
  /data/urban_thread_nov2025_oct2026.zip \
  --api-url https://attribution.example.com --concurrency 10 --wait
```

| Option | Purpose |
|--------|---------|
| `--api-url` / `ATTRIBUTION_API_URL` | Backend base URL (required) |
| `--api-key` / `ATTRIBUTION_API_KEY`, `--header 'Name: value'` | Auth headers, if the API is protected |
| `--only events.jsonl orders.csv ad_spend.csv` | Send a subset of files |
| `--concurrency`, `--timeout` | Parallelism (default 10) and per-request timeout |
| `--wait`, `--wait-timeout` | Poll `/api/processing/status` until no pending jobs |
| `--continue-on-error` | Log failed records and continue (default: stop on first failure) |
| `--dry-run` | Parse and count records only |

Retries on 429/5xx and timeouts (4 attempts, backoff). Re-running is safe: the
API is idempotent. Exit code is non-zero if any record failed.


`scripts/replay_attribution_data.py` is the sender. It reads dataset ZIPs (or
extracted directories) and POSTs each record to the running backend over HTTP:

| File in the ZIP   | Endpoint           |
|-------------------|--------------------|
| `events.jsonl`    | `POST /collect`         |
| `orders.csv`      | `POST /webhooks/orders` |
| `ad_spend.csv`    | `POST /ad-spend`        |

It never writes to PostgreSQL directly, so all validation, idempotency, and
attribution logic in the API is exercised. Only the Python 3 standard library is
needed.

### Local run

1. Start PostgreSQL and the backend (see [Run locally](#run-locally)).
2. Put the dataset ZIPs in `requirement/` (the default location):
   - `requirement/urbanthread_data_sep2026.zip`
   - `requirement/urban_thread_nov2025_oct2026.zip`
3. Run the sender:

```bash
python3 scripts/replay_attribution_data.py --wait
```

Files are streamed from the ZIP, so there is no need to unzip them.

### Options

| Option | Default | Purpose |
|--------|---------|---------|
| `--data PATH [PATH ...]` | both ZIPs in `requirement/` | ZIPs or extracted directories, anywhere on disk |
| `--base-url URL` | `http://localhost:8080` | Target backend |
| `--concurrency N` | `10` | Max requests in flight across all files |
| `--wait` | off | After sending, block until `/api/processing/status` shows no pending work |
| `--wait-timeout SECONDS` | `600` | Limit for `--wait` |

```bash
python3 scripts/replay_attribution_data.py --data requirement/urbanthread_data_sep2026.zip
python3 scripts/replay_attribution_data.py --concurrency 20 --wait
```

Transient failures (HTTP 429/500/502/503/504, timeouts) are retried up to 4
times with backoff. Any other error stops the run with a non-zero exit code.

### After deployment

The dataset is not bundled into the application or the Docker image, and it
should not be placed in `src/main/resources`. The sender is a separate client
that runs from any machine (a laptop, CI job, or bastion) with network access to
the deployed API:

```bash
python3 scripts/replay_attribution_data.py \
  --data /path/to/urbanthread_data_sep2026.zip /path/to/urban_thread_nov2025_oct2026.zip \
  --base-url https://attribution.example.com \
  --concurrency 5 --wait
```

Notes for deployed environments:

- **Safe to re-run.** Events are idempotent by `event_id` (repeats increment
  `dup_count`), order webhooks only apply when newer than the stored state, and
  ad-spend snapshots are append-only per `pulled_at`. A partial or interrupted
  run can simply be started again.
- **Order of data.** Late or out-of-order records are handled by the durable
  recompute queues, so sessions and attribution converge once processing drains.
  `--wait` confirms this.
- **Throttling.** Lower `--concurrency` for small instances or behind rate
  limits; 429/5xx responses are retried automatically.
- **Auth.** The API currently has no authentication. If a gateway or API key is
  added in front of it, the script needs matching headers (not supported yet).
- **Database access.** Run Flyway migrations through the app on startup; use
  [`queries.sql`](queries.sql) Part B (not Part A) on the migrated database.

### Production

The replay script is for demos, reviews, and one-off backfills. In production
the same three endpoints are fed continuously by the real sources, not by ZIPs:

| Source | Sends to |
|--------|----------|
| Website tracker / tag | `POST /collect` |
| Shop platform order webhooks | `POST /webhooks/orders` |
| Scheduled ad-platform spend pull (daily or hourly job) | `POST /ad-spend` |

One-time setup and operations:

1. Provision managed PostgreSQL 17 and set `PGHOST`, `PGPORT`, `PGDATABASE`,
   `PGUSER`, `PGPASSWORD` from a secret store (not the compose defaults).
2. Deploy the Spring Boot app. Flyway applies V1..V5 on first start, so
   `queries.sql` Part A is not needed.
3. Check `GET /actuator/health` and `GET /api/processing/status`.
4. Optional historical backfill: run the sender once from a trusted machine
   against the production URL with a low `--concurrency` and `--wait` (see
   [After deployment](#after-deployment)). It is idempotent, so a retry is safe.
5. Verify with `queries.sql` Part B, using a read-only database user.

Before exposing the API publicly, add authentication (for example an API
gateway or key check, plus webhook signature verification for orders), since the
ingestion endpoints are currently open.


Reviewers can run the verification and reporting queries in
[`queries.sql`](queries.sql) (health, data quality, identity, attribution
invariants, and channel reports):

```bash
psql -h <host> -U attribution -d attributioniq -f queries.sql
```

On a database the app has already migrated, run only Part B (the reviewer
queries); Part A is the schema for an empty database and would fail there:

```bash
sed -n '/^-- PART B — REVIEWER QUERIES/,$p' queries.sql | psql -h <host> -U attribution -d attributioniq
```

## Performance benchmark

Run the bounded synthetic event-ingestion benchmark against a running local API:

```bash
python3 scripts/benchmark_ingestion.py --requests 1000 --concurrency 1 5 10 20
```

The tool checks that the recompute queue is initially empty, sends unique
non-test website events through `POST /collect`, and waits for the queue to
drain after each concurrency profile. It reports accepted-request throughput,
HTTP latency percentiles, and queue-drain time. It does not change benchmark
data in place or reset the database; its synthetic records remain in the local
database for traceability. Run on a disposable/local database if that is not
desired.

### Initial local baseline

Environment: Apple M1, 8 CPU cores, Java 26.0.2, PostgreSQL 17.11, local API and
database. One 500-event/profile run measured:

| Concurrent requests | Events/second | p50 latency | p95 latency | p99 latency | Queue drain |
|---:|---:|---:|---:|---:|---:|
| 1 | 205.6 | 4.4 ms | 5.8 ms | 13.5 ms | 4.7 s |
| 5 | 401.0 | 9.7 ms | 28.8 ms | 43.5 ms | 7.4 s |
| 10 | 604.1 | 14.2 ms | 31.0 ms | 54.4 ms | 9.3 s |
| 20 | 561.0 | 29.5 ms | 85.6 ms | 107.4 ms | 7.4 s |

**Treat these figures as an initial local baseline, not a capacity claim.** This
is a single 500-event sample per profile, and does not model orders, identity
merges, ad-spend ingestion, multi-instance deployment, or sustained production
traffic. At concurrency 20 throughput fell from the concurrency-10 result while
tail latency rose, suggesting contention at this local setup; do not assume
more concurrent requests will improve throughput. A prior 250-event smoke run
used test events that skip session rebuilding and is intentionally excluded.
Performance depends on machine, database configuration, data shape, and
background load. For optimization comparisons, use the same host and database,
let the queue drain between profiles, and compare repeated runs plus p95/p99
latency and queue-drain time—not just peak requests per second.
