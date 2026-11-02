import pandas as pd
import streamlit as st

from attribution_dashboard.analytics import overview_channel_table
from attribution_dashboard.charts import attribution_donut
from attribution_dashboard.presentation import (
    get_report_context,
    render_kpis,
    render_page_intro,
    render_processing_status,
)


@st.fragment(run_every="5s")
def render_overview() -> None:
    filters, report = get_report_context()
    channels = report["channels"]

    render_page_intro(
        "Multi-channel attribution dashboard",
        "Compare marketing investment, attributed orders, and revenue across channels.",
        filters,
    )
    render_kpis(channels)
    render_processing_status()

    if channels.empty:
        st.info("No attribution data is available for this reporting period.")
    else:
        channel_metrics = overview_channel_table(channels)
        st.subheader("Channel performance")
        for row_start in range(0, len(channel_metrics), 4):
            card_columns = st.columns(4)
            for column, (_, channel) in zip(
                card_columns,
                channel_metrics.iloc[row_start : row_start + 4].iterrows(),
                strict=False,
            ):
                with column:
                    with st.container(border=True):
                        st.markdown(f"**{channel['Channel']}**")
                        st.metric("Spend", f"₹{channel['Ad spend (INR)']:,.0f}")
                        cpa = channel["Cost per order (INR)"]
                        st.metric(
                            "Cost per attributed order",
                            "—" if pd.isna(cpa) else f"₹{cpa:,.2f}",
                        )
                        st.caption(
                            f"{channel['Attributed orders']:,.0f} orders · "
                            f"₹{channel['Attributed revenue (INR)']:,.0f} revenue"
                        )

        chart_columns = st.columns(2)
        spend_mix = channel_metrics.loc[channel_metrics["Ad spend (INR)"] > 0]
        with chart_columns[0]:
            st.subheader("Spend by channel")
            if spend_mix.empty:
                st.info("No channel spend is available for this period.")
            else:
                st.altair_chart(
                    attribution_donut(spend_mix, "Ad spend (INR)"),
                    width="stretch",
                    alt="Share of recorded marketing spend by channel",
                )

        order_mix = channel_metrics.loc[channel_metrics["Attributed orders"] > 0]
        with chart_columns[1]:
            st.subheader("Attributed orders")
            if order_mix.empty:
                st.info("No attributed orders are available for this period.")
            else:
                st.altair_chart(
                    attribution_donut(order_mix, "Attributed orders"),
                    width="stretch",
                    alt="Share of attributed orders by channel",
                )

        st.subheader("Spend vs. cost per order")
        st.caption("Cost per order is ad spend divided by attributed orders.")
        paid_channels = channel_metrics.loc[
            channel_metrics["Ad spend (INR)"].gt(0)
            & channel_metrics["Cost per order (INR)"].notna()
        ]
        if paid_channels.empty:
            if channel_metrics["Ad spend (INR)"].gt(0).any():
                st.info(
                    "Spend is recorded, but those channels have no attributed orders "
                    "for this period, so cost per order cannot be calculated."
                )
            else:
                st.info(
                    "No channel spend is recorded for this period, so cost per order "
                    "cannot be calculated."
                )
        else:
            cost_chart_columns = st.columns(2)
            with cost_chart_columns[0]:
                st.caption("Ad spend by channel")
                st.bar_chart(
                    paid_channels,
                    x="Channel",
                    y="Ad spend (INR)",
                    horizontal=True,
                    height=220,
                    alt="Ad spend by paid channel",
                )
            with cost_chart_columns[1]:
                st.caption("Cost per attributed order")
                st.bar_chart(
                    paid_channels,
                    x="Channel",
                    y="Cost per order (INR)",
                    horizontal=True,
                    height=220,
                    alt="Cost per attributed order by paid channel",
                )

        st.subheader("Channel performance overview")
        st.caption(
            "ROAS is attributed revenue divided by spend. It is not shown for channels "
            "without recorded spend."
        )
        st.dataframe(
            channel_metrics,
            width="stretch",
            hide_index=True,
            alt="Channel performance overview with revenue, spend, orders, cost per order, and ROAS",
            column_config={
                "Attributed revenue (INR)": st.column_config.NumberColumn(
                    "Attributed revenue (INR)", format="₹%,.2f"
                ),
                "Ad spend (INR)": st.column_config.NumberColumn(
                    "Spend (INR)", format="₹%,.2f"
                ),
                "Attributed orders": st.column_config.NumberColumn(
                    "Attributed orders", format="%d"
                ),
                "Cost per order (INR)": st.column_config.NumberColumn(
                    "Cost per order (INR)", format="₹%,.2f"
                ),
                "ROAS": st.column_config.NumberColumn(
                    "ROAS",
                    format="%.2f×",
                    help=(
                        "Attributed revenue ÷ ad spend. For example, ₹500 revenue ÷ "
                        "₹100 spend = 5× ROAS. Higher means more attributed revenue "
                        "per rupee spent."
                    ),
                ),
            },
        )


render_overview()
