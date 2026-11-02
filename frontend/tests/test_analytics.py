import unittest

import pandas as pd

from attribution_dashboard.analytics import (
    channel_name,
    daily_table,
    daily_totals,
    overview_channel_table,
    summarize_channels,
)


class AnalyticsTests(unittest.TestCase):
    def test_summarize_channels_computes_blended_roas_and_leaders(self) -> None:
        channels = pd.DataFrame(
            [
                {
                    "channel": "paid_google",
                    "orders": 2,
                    "revenueInr": 400.0,
                    "spendInr": 100.0,
                    "roas": 4.0,
                },
                {
                    "channel": "email",
                    "orders": 3,
                    "revenueInr": 600.0,
                    "spendInr": 0.0,
                    "roas": None,
                },
            ]
        )

        summary = summarize_channels(channels)

        self.assertEqual(summary["orders"], 5)
        self.assertEqual(summary["revenue"], 1000.0)
        self.assertEqual(summary["spend"], 100.0)
        self.assertEqual(summary["roas"], 10.0)
        self.assertEqual(summary["top_revenue_channel"]["name"], "Email")
        self.assertEqual(summary["top_roas_channel"]["name"], "Google Ads")

    def test_empty_summary_has_no_roas_or_channel_leaders(self) -> None:
        channels = pd.DataFrame(
            columns=["channel", "orders", "revenueInr", "spendInr", "roas"]
        )

        summary = summarize_channels(channels)

        self.assertEqual(summary["orders"], 0)
        self.assertEqual(summary["revenue"], 0)
        self.assertIsNone(summary["roas"])
        self.assertIsNone(summary["top_revenue_channel"])
        self.assertIsNone(summary["top_roas_channel"])

    def test_summary_handles_channels_without_valid_revenue(self) -> None:
        channels = pd.DataFrame(
            [
                {
                    "channel": "paid_google",
                    "orders": 0,
                    "revenueInr": float("nan"),
                    "spendInr": 0.0,
                    "roas": float("nan"),
                }
            ]
        )

        summary = summarize_channels(channels)

        self.assertIsNone(summary["top_revenue_channel"])
        self.assertIsNone(summary["top_roas_channel"])

    def test_daily_totals_roll_up_channels_by_date(self) -> None:
        daily = pd.DataFrame(
            [
                {
                    "day": pd.Timestamp("2026-10-01"),
                    "revenueInr": 100.0,
                    "spendInr": 20.0,
                    "orders": 1,
                },
                {
                    "day": pd.Timestamp("2026-10-01"),
                    "revenueInr": 50.0,
                    "spendInr": 10.0,
                    "orders": 2,
                },
            ]
        )

        totals = daily_totals(daily)

        self.assertEqual(len(totals), 1)
        self.assertEqual(totals.iloc[0]["Revenue (INR)"], 150.0)
        self.assertEqual(totals.iloc[0]["Ad spend (INR)"], 30.0)
        self.assertEqual(totals.iloc[0]["Orders"], 3)

    def test_channel_names_are_human_readable(self) -> None:
        self.assertEqual(channel_name("organic_search"), "Organic search")
        self.assertEqual(channel_name("new_channel"), "New Channel")

    def test_overview_table_calculates_cost_per_order(self) -> None:
        channels = pd.DataFrame(
            [
                {
                    "channel": "paid_google",
                    "orders": 4,
                    "revenueInr": 800.0,
                    "spendInr": 120.0,
                    "roas": 800.0 / 120.0,
                },
                {
                    "channel": "email",
                    "orders": 2,
                    "revenueInr": 300.0,
                    "spendInr": 0.0,
                    "roas": None,
                },
            ]
        )

        table = overview_channel_table(channels)

        self.assertEqual(table.loc[0, "Channel"], "Google Ads")
        self.assertEqual(table.loc[0, "Cost per order (INR)"], 30.0)
        self.assertTrue(pd.isna(table.loc[1, "Cost per order (INR)"]))

    def test_daily_table_supports_empty_reports(self) -> None:
        daily = pd.DataFrame(
            columns=["day", "channel", "orders", "revenueInr", "spendInr", "roas"]
        )

        table = daily_table(daily)

        self.assertTrue(table.empty)
        self.assertEqual(
            list(table.columns),
            ["Date", "Channel", "Orders", "Revenue (INR)", "Ad spend (INR)", "ROAS"],
        )


if __name__ == "__main__":
    unittest.main()
