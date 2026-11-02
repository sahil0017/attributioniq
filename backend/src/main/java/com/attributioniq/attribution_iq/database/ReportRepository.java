package com.attributioniq.attribution_iq.database;

import com.attributioniq.attribution_iq.domain.AttributionModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Runs reporting SQL and maps database rows to report-domain records. */
@Repository
public class ReportRepository {
    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Calls channel_daily for inclusive UTC dates and the requested attribution model. */
    public List<DailyMetric> findDailyMetrics(LocalDate from, LocalDate to, AttributionModel model) {
        return jdbc.query("""
                SELECT day, channel, orders, revenue_inr, spend_inr, roas
                FROM channel_daily(?, ?, ?)
                ORDER BY day, channel
                """, (rs, rowNum) -> new DailyMetric(rs.getObject("day", LocalDate.class),
                rs.getString("channel"), rs.getLong("orders"), rs.getBigDecimal("revenue_inr"),
                rs.getBigDecimal("spend_inr"), rs.getBigDecimal("roas")), from, to, model.value());
    }

    /** Finds report bounds using counted last-click orders and current spend snapshots. */
    public DataRange findDataRange() {
        return jdbc.queryForObject("""
                SELECT min(day) AS data_from, max(day) AS data_to
                FROM (
                    SELECT (order_at AT TIME ZONE 'UTC')::date AS day
                    FROM order_attribution
                    WHERE counted AND model = 'last_click'
                    UNION ALL
                    SELECT spend_date AS day FROM ad_spend_current
                ) available_days
                """, (rs, rowNum) -> {
            LocalDate from = rs.getObject("data_from", LocalDate.class);
            LocalDate to = rs.getObject("data_to", LocalDate.class);
            return new DataRange(from, to);
        });
    }

    public record DailyMetric(LocalDate day, String channel, long orders, BigDecimal revenueInr,
                              BigDecimal spendInr, BigDecimal roas) { }

    public record DataRange(LocalDate from, LocalDate to) { }
}
