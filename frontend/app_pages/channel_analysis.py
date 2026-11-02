import streamlit as st

from attribution_dashboard.analytics import channel_table
from attribution_dashboard.presentation import (
    get_report_context,
    render_channel_table,
    render_kpis,
    render_page_intro,
)


@st.fragment(run_every="5s")
def render_channel_analysis() -> None:
    filters, report = get_report_context()
    channels = report["channels"]

    render_page_intro(
        "Channel analysis",
        "Compare channel contribution and efficiency to understand where results are coming from.",
        filters,
    )
    render_kpis(channels)

    if channels.empty:
        st.info("No channel data is available for this reporting period.")
    else:
        display = channel_table(channels)
        chart_columns = st.columns(2)
        with chart_columns[0]:
            st.subheader("Attributed revenue")
            revenue_data = display.sort_values("Revenue (INR)")
            st.bar_chart(
                revenue_data,
                x="Channel",
                y="Revenue (INR)",
                horizontal=True,
                alt="Attributed revenue compared across marketing channels",
            )
        with chart_columns[1]:
            st.subheader("Ad spend")
            spend_data = display.sort_values("Ad spend (INR)")
            st.bar_chart(
                spend_data,
                x="Channel",
                y="Ad spend (INR)",
                horizontal=True,
                alt="Marketing spend compared across channels",
            )

        st.subheader("Return on ad spend")
        st.caption(
            "ROAS compares attributed revenue with ad spend. Channels without recorded spend "
            "do not have a ROAS value."
        )
        roas_data = display.dropna(subset=["ROAS"]).sort_values("ROAS")
        if roas_data.empty:
            st.info("ROAS is not available because no channel has recorded ad spend.")
        else:
            st.bar_chart(
                roas_data,
                x="Channel",
                y="ROAS",
                horizontal=True,
                alt="Return on ad spend compared across channels with recorded spend",
            )

        st.subheader("Channel results")
        render_channel_table(display)


render_channel_analysis()
