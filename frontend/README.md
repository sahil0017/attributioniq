# AttributionIQ dashboard

A Streamlit multichannel attribution dashboard with three views:

- **Overview** — attributed revenue, ad spend, blended ROAS, orders, trends, and leading channels.
- **Channel analysis** — revenue, spend, and ROAS comparisons by marketing channel.
- **Report data** — read-only channel and daily detail tables.

## Run locally

Install the dependencies from `requirements.txt`, then start the app:

```bash
python3 -m venv .venv
.venv/bin/python3 -m pip install -r requirements.txt
.venv/bin/python3 -m streamlit run streamlit_app.py
```

The app expects the attribution API at `http://localhost:8080` by default. Set
`ATTRIBUTION_API_URL` to use another API base URL.

While a dashboard page is open, its report refreshes automatically every five
seconds. The Overview page also refreshes processing status and shows the latest
backend data-received time and source.

Report tables are read-only, with data export and download controls disabled.

## Project layout

- `streamlit_app.py` — navigation and shared report filters.
- `app_pages/` — overview, channel analysis, and report data pages.
- `attribution_dashboard/` — API access, KPI calculations, and shared presentation.
- `tests/` — unit tests for the analytics calculations.
