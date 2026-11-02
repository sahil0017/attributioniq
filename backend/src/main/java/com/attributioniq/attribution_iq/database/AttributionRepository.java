package com.attributioniq.attribution_iq.database;

import com.attributioniq.attribution_iq.domain.AttributionModel;
import com.attributioniq.attribution_iq.domain.AttributionReason;
import com.attributioniq.attribution_iq.domain.EventTimestampSource;
import com.attributioniq.attribution_iq.domain.LinkMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PostgreSQL persistence operations for raw input, identity/session derivation,
 * attribution, and durable recompute queues. Business orchestration belongs in
 * the service layer; callers normally provide transaction boundaries.
 */
@Repository
public class AttributionRepository {
    private final JdbcTemplate jdbc;

    public AttributionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean currencyExists(String currency) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM fx_rates WHERE currency = ?", Integer.class, currency);
        return count != null && count > 0;
    }

    /** Loads validated session, attribution-window, and device-clock settings. */
    public PipelineSettings loadPipelineSettings() {
        Map<String, String> values = new HashMap<>();
        RowCallbackHandler settingsRows = rs -> values.put(rs.getString("key"), rs.getString("value"));
        jdbc.query("SELECT key, value FROM settings WHERE key IN (?, ?, ?, ?)", settingsRows,
                "session_gap_minutes", "attribution_window_days", "device_ts_max_age_days",
                "device_ts_max_future_min");
        return new PipelineSettings(
                Duration.ofMinutes(setting(values, "session_gap_minutes")),
                Duration.ofDays(setting(values, "attribution_window_days")),
                Duration.ofDays(setting(values, "device_ts_max_age_days")),
                Duration.ofMinutes(setting(values, "device_ts_max_future_min")));
    }

    private static long setting(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) throw new IllegalStateException("Required settings row missing: " + key);
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new IllegalStateException("Setting must be positive: " + key);
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Setting must be an integer: " + key, exception);
        }
    }

    /** Inserts an event once per event ID; returns false when already present. */
    public boolean insertEvent(EventData event) {
        return jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO events (event_id, event_name, ts_raw, ts_device, received_at, event_ts, ts_source,
                        anonymous_id, email_hash, page_url, referrer, referrer_host, user_agent, ip, value, currency,
                        order_id, utm_source, utm_medium, utm_campaign, click_id_type, click_id, is_bot, is_test)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """);
            int i = 1;
            statement.setString(i++, event.eventId());
            statement.setString(i++, event.eventName());
            statement.setString(i++, event.rawTimestamp());
            timestamp(statement, i++, event.deviceTimestamp());
            timestamp(statement, i++, event.receivedAt());
            timestamp(statement, i++, event.eventTimestamp());
            statement.setString(i++, event.timestampSource().value());
            statement.setString(i++, event.anonymousId());
            statement.setString(i++, event.emailHash());
            statement.setString(i++, event.pageUrl());
            statement.setString(i++, event.referrer());
            statement.setString(i++, event.referrerHost());
            statement.setString(i++, event.userAgent());
            statement.setString(i++, event.ip());
            if (event.value() == null) statement.setNull(i++, Types.NUMERIC);
            else statement.setBigDecimal(i++, event.value());
            statement.setString(i++, event.currency());
            statement.setString(i++, event.orderId());
            statement.setString(i++, event.utmSource());
            statement.setString(i++, event.utmMedium());
            statement.setString(i++, event.utmCampaign());
            statement.setString(i++, event.clickIdType());
            statement.setString(i++, event.clickId());
            statement.setBoolean(i++, event.bot());
            statement.setBoolean(i, event.test());
            return statement;
        }) > 0;
    }

    public void incrementDuplicateEvent(String eventId) {
        jdbc.update("UPDATE events SET dup_count = dup_count + 1 WHERE event_id = ?", eventId);
    }

    /** Coalesces work so one pending rebuild covers all recent events for a visitor. */
    public void enqueueSessionRecompute(String anonymousId, String reason) {
        jdbc.update("""
                INSERT INTO session_recompute_queue
                    (anonymous_id, reason, enqueued_at, attempt_count, available_at, last_error)
                VALUES (?, ?, now(), 0, now(), NULL)
                ON CONFLICT (anonymous_id) DO UPDATE SET
                    reason = EXCLUDED.reason, enqueued_at = now(), attempt_count = 0,
                    available_at = now(), last_error = NULL
                """, anonymousId, reason);
    }

    /** Claims the oldest available visitor job without blocking another worker. */
    public String claimSessionRecompute() {
        List<String> keys = jdbc.query("""
                SELECT anonymous_id FROM session_recompute_queue
                WHERE available_at <= now()
                ORDER BY enqueued_at, anonymous_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> rs.getString(1));
        return keys.isEmpty() ? null : keys.getFirst();
    }

    public void completeSessionRecompute(String anonymousId) {
        jdbc.update("DELETE FROM session_recompute_queue WHERE anonymous_id = ?", anonymousId);
    }

    public void enqueuePersonRecompute(Long personId, String reason) {
        jdbc.update("""
                INSERT INTO person_recompute_queue
                    (person_id, reason, enqueued_at, attempt_count, available_at, last_error)
                VALUES (?, ?, now(), 0, now(), NULL)
                ON CONFLICT (person_id) DO UPDATE SET
                    reason = EXCLUDED.reason, enqueued_at = now(), attempt_count = 0,
                    available_at = now(), last_error = NULL
                """, personId, reason);
    }

    public Long claimPersonRecompute() {
        List<Long> keys = jdbc.query("""
                SELECT person_id FROM person_recompute_queue
                WHERE available_at <= now()
                ORDER BY enqueued_at, person_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> rs.getLong(1));
        return keys.isEmpty() ? null : keys.getFirst();
    }

    public void completePersonRecompute(Long personId) {
        jdbc.update("DELETE FROM person_recompute_queue WHERE person_id = ?", personId);
    }

    public void enqueueOrderRecompute(String orderId, String reason) {
        jdbc.update("""
                INSERT INTO order_recompute_queue
                    (order_id, reason, enqueued_at, attempt_count, available_at, last_error)
                VALUES (?, ?, now(), 0, now(), NULL)
                ON CONFLICT (order_id) DO UPDATE SET
                    reason = EXCLUDED.reason, enqueued_at = now(), attempt_count = 0,
                    available_at = now(), last_error = NULL
                """, orderId, reason);
    }

    public String claimOrderRecompute() {
        List<String> keys = jdbc.query("""
                SELECT order_id FROM order_recompute_queue
                WHERE available_at <= now()
                ORDER BY enqueued_at, order_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> rs.getString(1));
        return keys.isEmpty() ? null : keys.getFirst();
    }

    public void completeOrderRecompute(String orderId) {
        jdbc.update("DELETE FROM order_recompute_queue WHERE order_id = ?", orderId);
    }

    public void recordRecomputeFailure(String queue, String key, Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                ? "Unknown recompute failure" : failure.getMessage();
        String sql = switch (queue) {
            case "session" -> """
                    UPDATE session_recompute_queue
                    SET attempt_count = attempt_count + 1,
                        available_at = now() + make_interval(
                            secs => LEAST(3600, power(2, LEAST(attempt_count, 12)))::double precision),
                        last_error = left(?, 1000)
                    WHERE anonymous_id = ?
                    """;
            case "person" -> """
                    UPDATE person_recompute_queue
                    SET attempt_count = attempt_count + 1,
                        available_at = now() + make_interval(
                            secs => LEAST(3600, power(2, LEAST(attempt_count, 12)))::double precision),
                        last_error = left(?, 1000)
                    WHERE person_id = ?::bigint
                    """;
            case "order" -> """
                    UPDATE order_recompute_queue
                    SET attempt_count = attempt_count + 1,
                        available_at = now() + make_interval(
                            secs => LEAST(3600, power(2, LEAST(attempt_count, 12)))::double precision),
                        last_error = left(?, 1000)
                    WHERE order_id = ?
                    """;
            default -> throw new IllegalArgumentException("Unknown recompute queue: " + queue);
        };
        jdbc.update(sql, message, key);
    }

    public RecomputeQueueStatus recomputeQueueStatus() {
        return jdbc.queryForObject("""
                WITH latest_ingestion AS (
                    SELECT 'Website events' AS source, max(ingested_at) AS received_at FROM events
                    UNION ALL
                    SELECT 'Order webhooks', max(received_at) FROM order_webhooks
                    UNION ALL
                    SELECT 'Ad spend imports', max(pulled_at) FROM ad_spend_pulls
                )
                SELECT
                    (SELECT count(*) FROM session_recompute_queue) AS sessions,
                    (SELECT count(*) FROM person_recompute_queue) AS people,
                    (SELECT count(*) FROM order_recompute_queue) AS orders,
                    (SELECT count(*) FROM (
                        SELECT attempt_count FROM session_recompute_queue
                        UNION ALL SELECT attempt_count FROM person_recompute_queue
                        UNION ALL SELECT attempt_count FROM order_recompute_queue
                    ) q WHERE attempt_count > 0) AS retrying,
                    (SELECT min(enqueued_at) FROM (
                        SELECT enqueued_at FROM session_recompute_queue
                        UNION ALL SELECT enqueued_at FROM person_recompute_queue
                        UNION ALL SELECT enqueued_at FROM order_recompute_queue
                    ) q) AS oldest,
                    (SELECT received_at FROM latest_ingestion
                     ORDER BY received_at DESC NULLS LAST LIMIT 1) AS latest_data_at,
                    (SELECT source FROM latest_ingestion
                     ORDER BY received_at DESC NULLS LAST LIMIT 1) AS latest_data_source
                """, (rs, rowNum) -> {
            OffsetDateTime oldest = rs.getObject("oldest", OffsetDateTime.class);
            OffsetDateTime latestDataAt = rs.getObject("latest_data_at", OffsetDateTime.class);
            return new RecomputeQueueStatus(rs.getLong("sessions"), rs.getLong("people"),
                    rs.getLong("orders"), rs.getLong("retrying"),
                    oldest == null ? null : oldest.toInstant(),
                    latestDataAt == null ? null : latestDataAt.toInstant(),
                    rs.getString("latest_data_source"));
        });
    }

    public boolean recordOrderWebhook(OrderData order) {
        int inserted = jdbc.update("""
                INSERT INTO order_webhooks (order_id, updated_at, created_at, email_hash, total, currency,
                                            financial_status, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_id, updated_at) DO NOTHING
                """, order.orderId(), utc(order.updatedAt()), utc(order.createdAt()), order.emailHash(),
                order.total(), order.currency(), order.financialStatus(), utc(order.receivedAt()));
        if (inserted == 0) {
            return false;
        }
        jdbc.update("""
                INSERT INTO orders (order_id, created_at, updated_at, email_hash, total, currency,
                                    financial_status, is_test)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_id) DO UPDATE SET
                    created_at = CASE WHEN EXCLUDED.updated_at > orders.updated_at
                                      THEN EXCLUDED.created_at ELSE orders.created_at END,
                    updated_at = GREATEST(orders.updated_at, EXCLUDED.updated_at),
                    email_hash = CASE WHEN EXCLUDED.updated_at > orders.updated_at
                                      THEN EXCLUDED.email_hash ELSE orders.email_hash END,
                    total = CASE WHEN EXCLUDED.updated_at > orders.updated_at
                                 THEN EXCLUDED.total ELSE orders.total END,
                    currency = CASE WHEN EXCLUDED.updated_at > orders.updated_at
                                    THEN EXCLUDED.currency ELSE orders.currency END,
                    financial_status = CASE WHEN EXCLUDED.updated_at > orders.updated_at
                                            THEN EXCLUDED.financial_status ELSE orders.financial_status END,
                    is_test = CASE WHEN EXCLUDED.updated_at > orders.updated_at
                                   THEN EXCLUDED.is_test ELSE orders.is_test END,
                    webhook_count = orders.webhook_count + 1
                """, order.orderId(), utc(order.createdAt()), utc(order.updatedAt()), order.emailHash(),
                order.total(), order.currency(), order.financialStatus(), order.test());
        return true;
    }

    public boolean insertSpend(SpendData spend) {
        return jdbc.update("""
                INSERT INTO ad_spend_pulls (spend_date, platform, campaign, spend, currency, pulled_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (spend_date, platform, campaign, pulled_at) DO NOTHING
                """, spend.date(), spend.platform(), spend.campaign(), spend.spend(), spend.currency(),
                utc(spend.pulledAt())) > 0;
    }

    /** Creates/merges anonymous and email identities under transaction-scoped locks. */
    public Long linkIdentity(String anonymousId, String emailHash) {
        List<String> lockKeys = new ArrayList<>();
        lockKeys.add("anon:" + anonymousId);
        if (emailHash != null) lockKeys.add("email:" + emailHash);
        lockKeys.sort(Comparator.naturalOrder());
        for (String key : lockKeys) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    (ResultSetExtractor<Void>) resultSet -> { resultSet.next(); return null; }, key);
        }

        Long anonPerson = findPerson("anon", anonymousId);
        Long emailPerson = emailHash == null ? null : findPerson("email", emailHash);
        Long canonical = anonPerson != null ? anonPerson : emailPerson;
        if (canonical == null) {
            canonical = jdbc.queryForObject("INSERT INTO persons DEFAULT VALUES RETURNING person_id", Long.class);
        }
        if (anonPerson != null && emailPerson != null && !anonPerson.equals(emailPerson)) {
            canonical = mergePersons(anonPerson, emailPerson);
        }
        jdbc.update("INSERT INTO person_identifiers (id_type, id_value, person_id) VALUES ('anon', ?, ?) " +
                "ON CONFLICT (id_type, id_value) DO UPDATE SET person_id = EXCLUDED.person_id", anonymousId, canonical);
        if (emailHash != null) {
            jdbc.update("INSERT INTO person_identifiers (id_type, id_value, person_id) VALUES ('email', ?, ?) " +
                    "ON CONFLICT (id_type, id_value) DO UPDATE SET person_id = EXCLUDED.person_id", emailHash, canonical);
        }
        return Objects.requireNonNull(canonical, "Identity did not resolve to a person");
    }

    public Long findPerson(String type, String value) {
        if (value == null) return null;
        List<Long> personIds = jdbc.query("SELECT person_id FROM person_identifiers WHERE id_type = ? AND id_value = ?",
                (rs, rowNum) -> rs.getLong(1), type, value);
        return personIds.isEmpty() ? null : personIds.getFirst();
    }

    private Long mergePersons(Long firstPerson, Long secondPerson) {
        Long canonical = Math.min(firstPerson, secondPerson);
        Long merged = Math.max(firstPerson, secondPerson);
        jdbc.update("UPDATE person_identifiers SET person_id = ? WHERE person_id = ?", canonical, merged);
        jdbc.update("UPDATE sessions SET person_id = ? WHERE person_id = ?", canonical, merged);
        jdbc.update("UPDATE order_attribution SET person_id = ? WHERE person_id = ?", canonical, merged);
        jdbc.update("UPDATE persons SET merged_into = ? WHERE person_id = ?", canonical, merged);
        return canonical;
    }

    public void deleteSessions(String anonymousId) {
        jdbc.update("DELETE FROM sessions WHERE anonymous_id = ?", anonymousId);
    }

    /** Loads eligible non-bot, non-test events in deterministic event-time order. */
    public List<SessionEventData> findSessionEvents(String anonymousId) {
        return jdbc.query("""
                SELECT event_id, event_ts, page_url, referrer_host, utm_source, utm_medium,
                       utm_campaign, click_id_type
                FROM events
                WHERE anonymous_id = ? AND NOT is_bot AND NOT is_test
                ORDER BY event_ts, ingested_at, event_id
                """, (rs, rowNum) -> new SessionEventData(
                rs.getString("event_id"), rs.getObject("event_ts", OffsetDateTime.class).toInstant(),
                rs.getString("page_url"), rs.getString("referrer_host"), rs.getString("utm_source"),
                rs.getString("utm_medium"), rs.getString("utm_campaign"), rs.getString("click_id_type")), anonymousId);
    }

    public long insertSession(SessionData session) {
        String channel = jdbc.queryForObject("SELECT channel FROM classify_touch(?, ?, ?)", String.class,
                session.utmSource(), session.clickIdType(), session.referrerHost());
        String platform = jdbc.query("SELECT platform FROM classify_touch(?, ?, ?)",
                rs -> rs.next() ? rs.getString(1) : null,
                session.utmSource(), session.clickIdType(), session.referrerHost());
        Long id = jdbc.queryForObject("""
                INSERT INTO sessions (anonymous_id, person_id, started_at, ended_at, event_count,
                                      landing_url, utm_source, utm_medium, utm_campaign,
                                      click_id_type, referrer_host, channel, platform)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING session_id
                """, Long.class, session.anonymousId(), session.personId(), utc(session.startedAt()),
                utc(session.endedAt()), session.eventCount(), session.landingUrl(), session.utmSource(),
                session.utmMedium(), session.utmCampaign(), session.clickIdType(), session.referrerHost(), channel,
                platform);
        return Objects.requireNonNull(id, "Session insert did not return an ID");
    }

    public void linkEventsToSessions(List<EventSessionLink> links) {
        jdbc.batchUpdate("UPDATE events SET session_id = ? WHERE event_id = ?", links,
                links.size(), (statement, link) -> {
                    statement.setLong(1, link.sessionId());
                    statement.setString(2, link.eventId());
                });
    }

    public List<String> findOrderIdsForPerson(Long personId) {
        return jdbc.query("""
                SELECT DISTINCT o.order_id
                FROM orders o
                WHERE o.email_hash IN (
                    SELECT id_value FROM person_identifiers WHERE person_id = ? AND id_type = 'email'
                ) OR EXISTS (
                    SELECT 1 FROM events e
                    JOIN person_identifiers i ON i.id_type = 'anon' AND i.id_value = e.anonymous_id
                    WHERE e.order_id = o.order_id AND i.person_id = ?
                )
                ORDER BY o.order_id
                """, (rs, rowNum) -> rs.getString(1), personId, personId);
    }

    public OrderData findOrder(String orderId) {
        List<OrderData> orders = jdbc.query("""
                SELECT order_id, created_at, updated_at, email_hash, total, currency, financial_status, is_test
                FROM orders WHERE order_id = ?
                """, (rs, rowNum) -> new OrderData(
                rs.getString("order_id"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getString("email_hash"),
                rs.getBigDecimal("total"), rs.getString("currency"), rs.getString("financial_status"),
                rs.getBoolean("is_test"), null), orderId);
        return orders.isEmpty() ? null : orders.getFirst();
    }

    public Long findPersonForOrderEvent(String orderId) {
        List<Long> personIds = jdbc.query("""
                SELECT DISTINCT i.person_id
                FROM events e
                JOIN person_identifiers i ON i.id_type = 'anon' AND i.id_value = e.anonymous_id
                WHERE e.order_id = ?
                ORDER BY i.person_id
                LIMIT 1
                """, (rs, rowNum) -> rs.getLong(1), orderId);
        return personIds.isEmpty() ? null : personIds.getFirst();
    }

    /** Finds marketing sessions for a person inside the inclusive attribution window. */
    public List<TouchData> findTouches(Long personId, Instant orderAt, Instant windowStart) {
        return jdbc.query("""
                SELECT session_id, started_at, channel, platform, utm_campaign
                FROM sessions
                WHERE person_id = ? AND started_at <= ? AND started_at >= ?
                ORDER BY started_at, session_id
                """, (rs, rowNum) -> new TouchData(rs.getLong("session_id"),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(), rs.getString("channel"),
                rs.getString("platform"), rs.getString("utm_campaign")), personId, utc(orderAt), utc(windowStart));
    }

    public BigDecimal findFxRate(String currency) {
        return jdbc.queryForObject("SELECT to_inr FROM fx_rates WHERE currency = ?", BigDecimal.class, currency);
    }

    /** Upserts the computed attribution result for one order and model. */
    public void saveAttribution(AttributionData attribution) {
        jdbc.update("""
                INSERT INTO order_attribution (order_id, model, person_id, link_method, session_id,
                                               channel, platform, campaign, touch_at, order_at,
                                               window_days, reason, counted, revenue_inr, fx_rate, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (order_id, model) DO UPDATE SET
                    person_id = EXCLUDED.person_id, link_method = EXCLUDED.link_method,
                    session_id = EXCLUDED.session_id, channel = EXCLUDED.channel,
                    platform = EXCLUDED.platform, campaign = EXCLUDED.campaign,
                    touch_at = EXCLUDED.touch_at, order_at = EXCLUDED.order_at,
                    window_days = EXCLUDED.window_days, reason = EXCLUDED.reason,
                    counted = EXCLUDED.counted, revenue_inr = EXCLUDED.revenue_inr,
                    fx_rate = EXCLUDED.fx_rate, computed_at = now()
                """, attribution.orderId(), attribution.model().value(), attribution.personId(),
                attribution.linkMethod().value(), attribution.sessionId(), attribution.channel(),
                attribution.platform(), attribution.campaign(), nullableUtc(attribution.touchAt()),
                utc(attribution.orderAt()), attribution.windowDays(), attribution.reason().value(),
                attribution.counted(), attribution.revenueInr(), attribution.fxRate());
    }

    private static void timestamp(PreparedStatement statement, int index, Instant instant) throws SQLException {
        if (instant == null) statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        else statement.setObject(index, utc(instant));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime nullableUtc(Instant instant) {
        return instant == null ? null : utc(instant);
    }

    public record EventData(String eventId, String eventName, String rawTimestamp, Instant deviceTimestamp,
                            Instant receivedAt, Instant eventTimestamp, EventTimestampSource timestampSource,
                            String anonymousId, String emailHash, String pageUrl, String referrer,
                            String referrerHost, String userAgent, String ip, BigDecimal value, String currency,
                            String orderId, String utmSource, String utmMedium, String utmCampaign,
                            String clickIdType, String clickId, boolean bot, boolean test) { }

    public record OrderData(String orderId, Instant createdAt, Instant updatedAt, String emailHash,
                            BigDecimal total, String currency, String financialStatus, boolean test,
                            Instant receivedAt) { }

    public record SpendData(java.time.LocalDate date, String platform, String campaign, BigDecimal spend,
                            String currency, Instant pulledAt) { }

    public record SessionEventData(String eventId, Instant eventAt, String pageUrl, String referrerHost,
                                   String utmSource, String utmMedium, String utmCampaign, String clickIdType) { }

    public record SessionData(String anonymousId, Long personId, Instant startedAt, Instant endedAt,
                              int eventCount, String landingUrl, String utmSource, String utmMedium,
                              String utmCampaign, String clickIdType, String referrerHost) { }

    public record EventSessionLink(long sessionId, String eventId) { }

    public record TouchData(long sessionId, Instant startedAt, String channel, String platform, String campaign) { }

    public record AttributionData(String orderId, AttributionModel model, Long personId, LinkMethod linkMethod,
                                  Long sessionId, String channel, String platform, String campaign,
                                  Instant touchAt, Instant orderAt, int windowDays, AttributionReason reason,
                                  boolean counted, BigDecimal revenueInr, BigDecimal fxRate) { }

    public record PipelineSettings(Duration sessionGap, Duration attributionWindow,
                                   Duration maxDeviceTimestampAge, Duration maxDeviceTimestampFutureSkew) { }

    public record RecomputeQueueStatus(long sessionPending, long personPending, long orderPending,
                                       long retrying, Instant oldestEnqueuedAt,
                                       Instant lastDataReceivedAt, String lastDataReceivedBy) { }
}
