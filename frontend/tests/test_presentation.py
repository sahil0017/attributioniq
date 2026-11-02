import unittest

from attribution_dashboard.presentation import format_received_at


class ReceivedAtFormattingTests(unittest.TestCase):
    def test_formats_zulu_timestamp_as_utc(self) -> None:
        self.assertEqual(
            format_received_at("2026-10-29T19:41:31Z"),
            "2026-10-29 19:41:31 UTC",
        )

    def test_converts_offset_timestamp_to_utc(self) -> None:
        self.assertEqual(
            format_received_at("2026-10-30T01:11:31+05:30"),
            "2026-10-29 19:41:31 UTC",
        )

    def test_reports_unavailable_timestamp(self) -> None:
        self.assertEqual(format_received_at(None), "Unavailable")


if __name__ == "__main__":
    unittest.main()
