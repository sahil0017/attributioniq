from __future__ import annotations

import altair as alt
import pandas as pd


CHANNEL_COLORS = [
    "#1a73e8",
    "#ea8600",
    "#34a853",
    "#a142f4",
    "#fbbc04",
    "#12a594",
    "#d93025",
]


def attribution_donut(
    data: pd.DataFrame,
    value_column: str,
) -> alt.Chart:
    chart = (
        alt.Chart(data)
        .mark_arc(
            innerRadius=54,
            outerRadius=82,
            padAngle=0,
            cornerRadius=0,
            strokeWidth=0.75,
        )
        .encode(
            theta=alt.Theta(field=value_column, type="quantitative"),
            color=alt.Color(
                "Channel:N",
                scale=alt.Scale(range=CHANNEL_COLORS),
                legend=alt.Legend(
                    title=None,
                    orient="right",
                    labelAlign="left",
                    labelFontSize=13,
                    labelLimit=120,
                ),
            ),
            stroke=alt.Stroke(
                "Channel:N",
                scale=alt.Scale(range=CHANNEL_COLORS),
                legend=None,
            ),
            tooltip=[
                alt.Tooltip("Channel:N", title="Channel"),
                alt.Tooltip(f"{value_column}:Q", title=value_column, format=",.2f"),
            ],
        )
        .properties(height=300)
    )
    return chart
