from __future__ import annotations

from typing import Any

import pandas as pd

CHANNEL_NAMES = {
    "direct": "Direct",
    "email": "Email",
    "organic_search": "Organic search",
    "paid_google": "Google Ads",
    "paid_meta": "Meta Ads",
    "paid_tiktok": "TikTok Ads",
    "unattributed": "Unattributed",
}


def channel_name(value: str) -> str:
    return CHANNEL_NAMES.get(value, value.replace("_", " ").title())


def summarize_channels(channels: pd.DataFrame) -> dict[str, Any]:
    if channels.empty:
        return {
            "orders": 0,
            "revenue": 0.0,
            "spend": 0.0,
            "roas": None,
            "top_revenue_channel": None,
            "top_roas_channel": None,
        }

    orders = int(channels["orders"].fillna(0).sum())
    revenue = float(channels["revenueInr"].fillna(0).sum())
    spend = float(channels["spendInr"].fillna(0).sum())

    revenue_candidates = channels.dropna(subset=["revenueInr"])
    top_revenue_row = (
        revenue_candidates.loc[revenue_candidates["revenueInr"].idxmax()]
        if not revenue_candidates.empty
        else None
    )
    channels_with_spend = channels.loc[
        channels["spendInr"].gt(0) & channels["roas"].notna()
    ]
    top_roas_row = (
        channels_with_spend.loc[channels_with_spend["roas"].idxmax()]
        if not channels_with_spend.empty
        else None
    )

    return {
        "orders": orders,
        "revenue": revenue,
        "spend": spend,
        "roas": revenue / spend if spend else None,
        "top_revenue_channel": (
            {
                "name": channel_name(str(top_revenue_row["channel"])),
                "revenue": float(top_revenue_row["revenueInr"]),
            }
            if top_revenue_row is not None
            else None
        ),
        "top_roas_channel": (
            {
                "name": channel_name(str(top_roas_row["channel"])),
                "roas": float(top_roas_row["roas"]),
            }
            if top_roas_row is not None
            else None
        ),
    }


def daily_totals(daily: pd.DataFrame) -> pd.DataFrame:
    columns = ["Date", "Revenue (INR)", "Ad spend (INR)", "Orders"]
    if daily.empty:
        return pd.DataFrame(columns=columns)

    totals = (
        daily.groupby("day", as_index=False)[
            ["revenueInr", "spendInr", "orders"]
        ]
        .sum()
        .sort_values("day")
    )
    return totals.rename(
        columns={
            "day": "Date",
            "revenueInr": "Revenue (INR)",
            "spendInr": "Ad spend (INR)",
            "orders": "Orders",
        }
    )


def channel_table(channels: pd.DataFrame) -> pd.DataFrame:
    display = channels[
        ["channel", "orders", "revenueInr", "spendInr", "roas"]
    ].copy()
    return display.rename(
        columns={
            "channel": "Channel",
            "orders": "Orders",
            "revenueInr": "Revenue (INR)",
            "spendInr": "Ad spend (INR)",
            "roas": "ROAS",
        }
    ).assign(Channel=lambda frame: frame["Channel"].map(channel_name))


def overview_channel_table(channels: pd.DataFrame) -> pd.DataFrame:
    display = channel_table(channels).rename(
        columns={
            "Orders": "Attributed orders",
            "Revenue (INR)": "Attributed revenue (INR)",
        }
    )
    display["Cost per order (INR)"] = display["Ad spend (INR)"].div(
        display["Attributed orders"].where(
            display["Attributed orders"].gt(0) & display["Ad spend (INR)"].gt(0)
        )
    )
    return display[
        [
            "Channel",
            "Attributed revenue (INR)",
            "Ad spend (INR)",
            "Attributed orders",
            "Cost per order (INR)",
            "ROAS",
        ]
    ]


def daily_table(daily: pd.DataFrame) -> pd.DataFrame:
    display = daily[
        ["day", "channel", "orders", "revenueInr", "spendInr", "roas"]
    ].copy()
    if not display.empty:
        display["day"] = display["day"].dt.strftime("%Y-%m-%d")
    return display.rename(
        columns={
            "day": "Date",
            "channel": "Channel",
            "orders": "Orders",
            "revenueInr": "Revenue (INR)",
            "spendInr": "Ad spend (INR)",
            "roas": "ROAS",
        }
    ).assign(Channel=lambda frame: frame["Channel"].map(channel_name))
