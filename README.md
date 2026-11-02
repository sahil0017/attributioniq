# AttributionIQ

[![CI](https://github.com/sahil0017/attributioniq/actions/workflows/ci.yml/badge.svg)](https://github.com/sahil0017/attributioniq/actions/workflows/ci.yml)

First-party attribution pipeline: website events, order webhooks and ad spend
in; channel revenue / spend / ROAS out (last-click and first-click).

| Folder | Contents |
|--------|----------|
| [`backend/`](backend/README.md) | Java 21 + Spring Boot 4 API, PostgreSQL, Flyway migrations, ingestion scripts (`backend/scripts/`), reviewer SQL (`backend/queries.sql`) |
| [`frontend/`](frontend/README.md) | Streamlit dashboard that reads the backend report API |

## Live demo (Railway)

| What | URL |
|------|-----|
| Dashboard | https://frontend-production-22b1.up.railway.app |
| API base | https://backend-production-f1328.up.railway.app |
| Swagger UI | https://backend-production-f1328.up.railway.app/swagger-ui/index.html |
| Health | https://backend-production-f1328.up.railway.app/actuator/health |

## API

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/collect` | Website events |
| POST | `/webhooks/orders` | Order create/update webhooks |
| POST | `/ad-spend` | Daily ad spend |
| GET | `/api/reports/channels?from=&to=&model=last_click\|first_click` | Channel report |
| GET | `/api/reports/data-range` | First / last report date held |
| GET | `/api/processing/status` | Recompute backlog and last ingestion |

## Run locally

```bash
# 1. backend (needs Docker for PostgreSQL)
cd backend
docker compose up -d --wait postgres
./mvnw spring-boot:run

# 2. load data (another terminal)
python3 backend/scripts/ingest_dataset.py backend/requirement/urbanthread_data_sep2026.zip \
  --api-url http://localhost:8080 --wait

# 3. dashboard (another terminal)
cd frontend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
ATTRIBUTION_API_URL=http://localhost:8080 streamlit run streamlit_app.py
```

Dashboard: http://localhost:8501. Its default period is **All loaded data**,
taken from `/api/reports/data-range`, so historical datasets show immediately.

Or build the SQL by hand: `psql -d attributioniq -f backend/queries.sql`
(Part A = schema, Part B = reviewer queries).

## Configuration

| Variable | Service | Default | Purpose |
|----------|---------|---------|---------|
| `PGHOST` `PGPORT` `PGDATABASE` `PGUSER` `PGPASSWORD` | backend | localhost / 5432 / attributioniq / attribution / local dev password | PostgreSQL connection |
| `PORT` | backend, frontend | 8080 / 8501 | Listen port |
| `ATTRIBUTION_RECOMPUTE_DELAY_MS` | backend | 100 | Recompute worker poll delay |
| `ATTRIBUTION_RECOMPUTE_MAX_JOBS_PER_CYCLE` | backend | 50 | Jobs per worker cycle |
| `ATTRIBUTION_API_URL` | frontend | http://localhost:8080 | Backend base URL |

## Runtime behaviour

- Ingestion endpoints return `202 Accepted`; attribution and sessions are
  recomputed asynchronously by a durable, DB-backed queue worker, so restarts
  lose no work. Re-sending the same events/orders is idempotent.
- Flyway runs migrations on startup; Hibernate only validates the schema.
- Check progress with `/api/processing/status` (all `pending*` = 0 means
  reports are up to date) or `ingest_dataset.py --wait`.
- The API has no authentication (take-home scope). Put an API key or gateway
  in front before exposing it for real traffic.

## Deployment (Railway)

Three services in one Railway project, all deployed from this repo's `main`:

| Service | Root directory | Notes |
|---------|----------------|-------|
| Postgres | n/a | Railway Postgres template |
| backend | `/backend` | Dockerfile; `PG*` vars reference the Postgres service; `PORT=8080`; healthcheck `/actuator/health` |
| frontend | `/frontend` | Dockerfile; `ATTRIBUTION_API_URL=http://${{backend.RAILWAY_PRIVATE_DOMAIN}}:8080` |

Pushing to `main` redeploys both services. Load data into a deployment with
`ingest_dataset.py --api-url <backend-url> --wait`
(see [`backend/scripts/README.md`](backend/scripts/README.md)).

## CI

`.github/workflows/ci.yml` runs on every push and pull request:
backend tests against a PostgreSQL 17 service (includes Flyway migrations),
dashboard unit tests and a smoke run, sender-script checks, and Docker builds
of both images. Dependabot keeps Maven, pip, Docker and Actions dependencies
current.
