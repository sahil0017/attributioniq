from __future__ import annotations

from datetime import date, datetime, timezone
from typing import Any

import pandas as pd
import streamlit as st

from attribution_dashboard.analytics import summarize_channels
from attribution_dashboard.api import load_processing_status, load_report


def get_report_context() -> tuple[dict[str, Any], dict[str, Any]]:
    filters = st.session_state["report_filters"]
    start_date = filters["start_date"]
    end_date = filters["end_date"]

    if not isinstance(start_date, date) or not isinstance(end_date, date):
        st.info("Select both a start and end date in the sidebar.")
        st.stop()
    if start_date > end_date:
        st.error("The start date must be on or before the end date.")
        st.stop()

    try:
        report = load_report(start_date, end_date, filters["model"])
    except RuntimeError as error:
        st.error(f"Unable to load the attribution report: {error}", title="Report unavailable")
        st.stop()
    return filters, report


def render_page_intro(
    title: str,
    description: str,
    filters: dict[str, Any],
) -> None:
    header_columns = st.columns([3, 2], vertical_alignment="top")
    with header_columns[0]:
        st.title(title)
        st.caption(description)
        start_date = filters["start_date"]
        end_date = filters["end_date"]
        model_name = {
            "last_click": "Last click",
            "first_click": "First click",
        }.get(filters["model"], filters["model"])
        st.caption(
            f"Reporting period: {start_date:%b %d, %Y} – {end_date:%b %d, %Y} "
            f"· Attribution model: {model_name}"
        )

    try:
        status = load_processing_status()
    except RuntimeError as error:
        st.session_state["processing_status"] = None
        st.session_state["processing_status_error"] = str(error)
        received_label = "Unavailable"
        source = None
    else:
        st.session_state["processing_status"] = status
        st.session_state.pop("processing_status_error", None)
        received_label = format_received_at(status.get("lastDataReceivedAt"))
        source = status.get("lastDataReceivedBy")

    with header_columns[1]:
        st.markdown("**Latest data received**", text_alignment="right")
        st.caption(
            received_label + (f" · Source: {source}" if source else ""),
            text_alignment="right",
        )


def format_received_at(value: Any) -> str:
    if not value:
        return "Unavailable"
    received_at = str(value)
    try:
        parsed_received_at = datetime.fromisoformat(
            received_at.replace("Z", "+00:00")
        )
    except ValueError:
        return received_at

    if parsed_received_at.tzinfo is not None:
        return parsed_received_at.astimezone(timezone.utc).strftime(
            "%Y-%m-%d %H:%M:%S UTC"
        )
    return f"{parsed_received_at:%Y-%m-%d %H:%M:%S} (timezone unspecified)"


def render_kpis(channels: pd.DataFrame) -> dict[str, Any]:
    summary = summarize_channels(channels)
    with st.container(horizontal=True):
        st.metric(
            "Attributed revenue",
            f"₹{summary['revenue']:,.2f}",
            border=True,
            help="Revenue assigned to marketing channels under the selected attribution model.",
        )
        st.metric(
            "Ad spend",
            f"₹{summary['spend']:,.2f}",
            border=True,
            help="Marketing spend recorded for the selected reporting period.",
        )
        st.metric(
            "Blended ROAS",
            "—" if summary["roas"] is None else f"{summary['roas']:.2f}×",
            border=True,
            help="Attributed revenue divided by ad spend. A value above 1× means attributed revenue exceeds spend.",
        )
        st.metric(
            "Attributed orders",
            f"{summary['orders']:,}",
            border=True,
            help="Orders credited to marketing channels under the selected attribution model.",
        )
    return summary


def render_channel_table(channels: pd.DataFrame) -> None:
    display = channels.copy()
    st.dataframe(
        display,
        width="stretch",
        hide_index=True,
        alt="Channel performance with attributed orders, revenue, spend, and return on ad spend",
        column_config={
            "Orders": st.column_config.NumberColumn("Orders", format="%d"),
            "Revenue (INR)": st.column_config.NumberColumn(
                "Revenue (INR)", format="₹%,.2f"
            ),
            "Ad spend (INR)": st.column_config.NumberColumn(
                "Ad spend (INR)", format="₹%,.2f"
            ),
            "ROAS": st.column_config.NumberColumn("ROAS", format="%.2f×"),
        },
    )


def render_daily_table(daily: pd.DataFrame) -> None:
    display = daily.copy()
    st.dataframe(
        display,
        width="stretch",
        hide_index=True,
        alt="Daily attribution details by channel",
        column_config={
            "Orders": st.column_config.NumberColumn("Orders", format="%d"),
            "Revenue (INR)": st.column_config.NumberColumn(
                "Revenue (INR)", format="₹%,.2f"
            ),
            "Ad spend (INR)": st.column_config.NumberColumn(
                "Ad spend (INR)", format="₹%,.2f"
            ),
            "ROAS": st.column_config.NumberColumn("ROAS", format="%.2f×"),
        },
    )


def render_processing_status() -> None:
    status = st.session_state.get("processing_status")
    if status is None:
        error = st.session_state.get(
            "processing_status_error",
            "The processing status is unavailable.",
        )
        st.warning(f"Could not confirm report freshness: {error}", title="Freshness unavailable")
        return

    queue_keys = ("pendingSessions", "pendingPeople", "pendingOrders")
    pending = sum(int(status.get(key, 0) or 0) for key in queue_keys)
    retrying = int(status.get("retryingJobs", 0) or 0)
    if pending:
        st.warning(
            f"{pending:,} attribution updates are still processing "
            f"({retrying:,} retrying). Recent results may change.",
            title="Data is still processing",
        )
    else:
        st.caption("Data processing: up to date")
