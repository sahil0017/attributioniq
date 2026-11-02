from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

import streamlit as st

st.set_page_config(
    page_title="AttributionIQ",
    page_icon=":material/analytics:",
    layout="wide",
)

page = st.navigation(
    [
        st.Page(
            "app_pages/overview.py",
            title="Overview",
            icon=":material/dashboard:",
            default=True,
        ),
        st.Page(
            "app_pages/channel_analysis.py",
            title="Channel analysis",
            icon=":material/bar_chart:",
        ),
        st.Page(
            "app_pages/report_data.py",
            title="Report data",
            icon=":material/table_chart:",
        ),
    ],
    position="top",
)

today_utc = datetime.now(timezone.utc).date()
periods = {
    "Last 7 days": 7,
    "Last 30 days": 30,
    "Last 90 days": 90,
    "Custom range": None,
}
model_names = {
    "last_click": "Last click",
    "first_click": "First click",
}

with st.container(border=True):
    filter_columns = st.columns([1.1, 2.2, 1.1, 0.8], vertical_alignment="bottom")
    selected_period = filter_columns[0].selectbox(
        "Reporting period",
        options=list(periods),
        index=1,
    )

    if selected_period == "Custom range":
        default_start = today_utc - timedelta(days=29)
        selected_dates = filter_columns[1].date_input(
            "Date range",
            value=(default_start, today_utc),
            min_value=date(2020, 1, 1),
            max_value=today_utc,
            help="Dates are inclusive and use UTC order dates.",
        )
        if isinstance(selected_dates, tuple) and len(selected_dates) == 2:
            start_date, end_date = selected_dates
        else:
            start_date, end_date = None, None
    else:
        period_days = periods[selected_period]
        end_date = today_utc
        start_date = end_date - timedelta(days=period_days - 1)
        filter_columns[1].caption(
            f"Date range: {start_date:%b %d, %Y} – {end_date:%b %d, %Y}"
        )

    model = filter_columns[2].selectbox(
        "Attribution model",
        options=list(model_names),
        format_func=model_names.get,
    )
    if filter_columns[3].button(
        "Refresh",
        icon=":material/refresh:",
        width="stretch",
    ):
        st.cache_data.clear()

st.session_state["report_filters"] = {
    "start_date": start_date,
    "end_date": end_date,
    "model": model,
    "period": selected_period,
}

page.run()
