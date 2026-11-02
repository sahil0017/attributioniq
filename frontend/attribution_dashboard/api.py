from __future__ import annotations

import os
from datetime import date
from typing import Any

import pandas as pd
import requests
import streamlit as st

API_URL = os.getenv("ATTRIBUTION_API_URL", "http://localhost:8080").rstrip("/")
REPORT_URL = f"{API_URL}/api/reports/channels"
PROCESSING_URL = f"{API_URL}/api/processing/status"
DATA_RANGE_URL = f"{API_URL}/api/reports/data-range"
REQUEST_TIMEOUT_SECONDS = 10

CHANNEL_COLUMNS = ["channel", "orders", "revenueInr", "spendInr", "roas"]
DAILY_COLUMNS = ["day", "channel", "orders", "revenueInr", "spendInr", "roas"]


@st.cache_data(ttl=4, max_entries=64, show_spinner=False)
def get_json(url: str, params: dict[str, str] | None = None) -> Any:
    try:
        response = requests.get(url, params=params, timeout=REQUEST_TIMEOUT_SECONDS)
    except requests.RequestException as error:
        raise RuntimeError(f"Could not connect to the attribution service: {error}") from error

    if not response.ok:
        detail = response.text[:500] or response.reason
        raise RuntimeError(f"Attribution service returned HTTP {response.status_code}: {detail}")

    try:
        return response.json()
    except requests.JSONDecodeError as error:
        raise RuntimeError("Attribution service returned invalid JSON.") from error


def _records_frame(
    records: list[dict[str, Any]],
    columns: list[str],
    record_name: str,
) -> pd.DataFrame:
    if not all(isinstance(record, dict) for record in records):
        raise RuntimeError(f"Attribution report contains invalid {record_name} records.")
    return pd.DataFrame.from_records(records, columns=columns)


@st.cache_data(ttl=4, max_entries=64, show_spinner=False)
def load_report(start_date: date, end_date: date, model: str) -> dict[str, Any]:
    payload = get_json(
        REPORT_URL,
        {
            "from": start_date.isoformat(),
            "to": end_date.isoformat(),
            "model": model,
        },
    )
    if (
        not isinstance(payload, dict)
        or not isinstance(payload.get("channels"), list)
        or not isinstance(payload.get("daily"), list)
    ):
        raise RuntimeError("Attribution service returned an unexpected report format.")

    channels = _records_frame(payload["channels"], CHANNEL_COLUMNS, "channel")
    daily = _records_frame(payload["daily"], DAILY_COLUMNS, "daily")

    for frame in (channels, daily):
        for column in ("orders", "revenueInr", "spendInr", "roas"):
            frame[column] = pd.to_numeric(frame[column], errors="coerce")

    calculated_roas = channels["revenueInr"].div(channels["spendInr"]).where(
        channels["spendInr"] > 0
    )
    channels["roas"] = channels["roas"].combine_first(calculated_roas)
    channels = channels.sort_values(
        "revenueInr", ascending=False, na_position="last"
    ).reset_index(drop=True)
    daily["day"] = pd.to_datetime(daily["day"], errors="coerce")
    daily = daily.sort_values(["day", "channel"]).reset_index(drop=True)

    return {
        "channels": channels,
        "daily": daily,
        "from": payload.get("from", start_date.isoformat()),
        "to": payload.get("to", end_date.isoformat()),
        "model": payload.get("model", model),
    }


@st.cache_data(ttl=4, max_entries=4, show_spinner=False)
def load_processing_status() -> dict[str, Any]:
    payload = get_json(PROCESSING_URL)
    if not isinstance(payload, dict):
        raise RuntimeError("Processing service returned an unexpected status format.")
    return payload


@st.cache_data(ttl=30, max_entries=1, show_spinner=False)
def load_data_range() -> tuple[date, date] | None:
    """Return the first and last report dates held by the backend, or None if empty."""
    try:
        payload = get_json(DATA_RANGE_URL)
        return date.fromisoformat(payload["from"]), date.fromisoformat(payload["to"])
    except (RuntimeError, KeyError, TypeError, ValueError):
        return None
