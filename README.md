# AttributionIQ

First-party attribution pipeline: events, order webhooks and ad spend in,
channel revenue / spend / ROAS out.

| Folder | Contents |
|--------|----------|
| [`backend/`](backend/README.md) | Java 21 + Spring Boot 4 API, PostgreSQL 17, Flyway migrations, ingestion scripts (`backend/scripts/`), reviewer SQL (`backend/queries.sql`) |
| [`frontend/`](frontend/README.md) | Streamlit dashboard that reads the backend report API |

## Quick start (local)

```bash
# backend
cd backend
docker compose up -d --wait postgres
./mvnw spring-boot:run

# load data (another terminal)
python3 backend/scripts/ingest_dataset.py backend/requirement/urbanthread_data_sep2026.zip \
  --api-url http://localhost:8080 --wait

# dashboard (another terminal)
cd frontend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
ATTRIBUTION_API_URL=http://localhost:8080 streamlit run streamlit_app.py
```
