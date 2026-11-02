import streamlit as st

from attribution_dashboard.analytics import channel_table, daily_table
from attribution_dashboard.presentation import (
    get_report_context,
    render_channel_table,
    render_daily_table,
    render_page_intro,
)


@st.fragment(run_every="5s")
def render_report_data() -> None:
    filters, report = get_report_context()
    render_page_intro(
        "Report Data",
        "Review the underlying attribution results for this reporting period.",
        filters,
    )

    view = st.selectbox(
        "Data to view",
        options=["Daily Channel Detail", "Channel Summary"],
    )

    if view == "Daily Channel Detail":
        table = daily_table(report["daily"])
        if table.empty:
            st.info("No daily attribution details are available for this reporting period.")
        else:
            st.caption("Each row represents one date and one attribution channel.")
            render_daily_table(table)
    else:
        table = channel_table(report["channels"])
        if table.empty:
            st.info("No channel summary is available for this reporting period.")
        else:
            st.caption("Each row summarizes the selected period for one attribution channel.")
            render_channel_table(table)


render_report_data()
