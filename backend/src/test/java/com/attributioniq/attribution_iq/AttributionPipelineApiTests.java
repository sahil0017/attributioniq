package com.attributioniq.attribution_iq;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.attributioniq.attribution_iq.service.AttributionRecomputeProcessor;
import com.attributioniq.attribution_iq.database.AttributionRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AttributionPipelineApiTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AttributionRecomputeProcessor recomputeProcessor;

    @Autowired
    private AttributionRepository repository;

    @Test
    void exposesOpenApiDocumentationForPublicApiEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("AttributionIQ API"))
                .andExpect(jsonPath("$.paths['/collect'].post").exists())
                .andExpect(jsonPath("$.paths['/webhooks/orders'].post").exists())
                .andExpect(jsonPath("$.paths['/api/reports/channels'].get").exists());
    }

    @Test
    void processingStatusShowsLatestIngestionSourceAndTime() throws Exception {
        String identity = UUID.randomUUID().toString();
        Instant receivedAt = Instant.now();
        postEvent("freshness-" + identity, identity, null, receivedAt, receivedAt,
                "google", null).andExpect(status().isAccepted());

        mockMvc.perform(get("/api/processing/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastDataReceivedAt").isNotEmpty())
                .andExpect(jsonPath("$.lastDataReceivedBy").value("Website events"));
    }

    @Test
    void acceptsEventsAndPaidOrdersAndReportsAttributedRevenue() throws Exception {
        String identity = UUID.randomUUID().toString();
        String eventId = "event-" + identity;
        String orderId = "order-" + identity;
        String emailHash = "hash-" + identity;
        Instant eventAt = Instant.now().minusSeconds(120);
        Instant orderAt = Instant.now().minusSeconds(30);

        String event = """
                {"event_id":"%s","event_name":"page_view","timestamp":"%s",
                 "anonymous_id":"%s","email_hash":"%s","utm_source":"google",
                 "utm_campaign":"spring-test"}
                """.formatted(eventId, eventAt, identity, emailHash);
        mockMvc.perform(post("/collect").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isAccepted())
                .andExpect(content().string(containsString("accepted")));
        assertCount("SELECT count(*) FROM session_recompute_queue WHERE anonymous_id = ?", 1, identity);
        mockMvc.perform(post("/collect").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("duplicate")));

        String order = """
                {"order_id":"%s","created_at":"%s","updated_at":"%s",
                 "email_hash":"%s","total":1250.00,"currency":"INR","financial_status":"paid"}
                """.formatted(orderId, orderAt, orderAt, emailHash);
        mockMvc.perform(post("/webhooks/orders").contentType(MediaType.APPLICATION_JSON).content(order))
                .andExpect(status().isAccepted())
                .andExpect(content().string(containsString("accepted")));
        assertCount("SELECT count(*) FROM person_recompute_queue WHERE person_id = "
                + "(SELECT person_id FROM person_identifiers WHERE id_type = 'email' AND id_value = ?)",
                1, emailHash);

        LocalDate reportDate = orderAt.atZone(ZoneOffset.UTC).toLocalDate();
        String spend = """
                {"spend_date":"%s","platform":"google","campaign":"spring-test",
                 "spend":100.00,"currency":"INR"}
                """.formatted(reportDate);
        mockMvc.perform(post("/ad-spend").contentType(MediaType.APPLICATION_JSON).content(spend))
                .andExpect(status().isAccepted());

        drainRecomputes();

        mockMvc.perform(get("/api/reports/channels")
                        .param("from", reportDate.toString())
                        .param("to", reportDate.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("paid_google")))
                .andExpect(content().string(containsString("1250.00")))
                .andExpect(content().string(containsString("100.00")))
                .andExpect(content().string(containsString("12.50")));

        assertCount("SELECT count(*) FROM events WHERE event_id = ?", 1, eventId);
        assertCount("SELECT count(DISTINCT p.person_id) FROM persons p "
                + "JOIN person_identifiers i USING (person_id) WHERE i.id_value = ?", 1, identity);
        assertCount("SELECT count(*) FROM person_identifiers WHERE id_value IN (?, ?)",
                2, identity, emailHash);
        assertCount("SELECT count(*) FROM sessions WHERE anonymous_id = ?", 1, identity);
        assertCount("SELECT count(*) FROM orders WHERE order_id = ?", 1, orderId);
        assertCount("SELECT count(*) FROM order_webhooks WHERE order_id = ?", 1, orderId);
        assertCount("SELECT count(*) FROM ad_spend_pulls WHERE campaign = 'spring-test'", 1);
        assertCount("SELECT count(*) FROM order_attribution WHERE order_id = ?", 2, orderId);
        assertCount("SELECT count(*) FROM session_recompute_queue WHERE anonymous_id = ?", 0, identity);
        assertCount("SELECT count(*) FROM person_recompute_queue WHERE person_id = "
                + "(SELECT person_id FROM person_identifiers WHERE id_type = 'email' AND id_value = ?)",
                0, emailHash);
        assertCount("SELECT count(*) FROM order_recompute_queue WHERE order_id = ?", 0, orderId);
    }

    @Test
    void acceptsHistoricalEventsAndExtractsCampaignMetadataFromPageUrl() throws Exception {
        String identity = UUID.randomUUID().toString();
        String eventId = "historical-event-" + identity;
        String eventAt = "2026-09-10T12:00:00Z";
        String receivedAt = "2026-09-10T12:00:03Z";
        String event = """
                {"event_id":"%s","event_name":"page_view","ts":"%s",
                 "received_at":"%s","anonymous_id":"%s",
                 "page_url":"https://www.urbanthread.in/?utm_source=google&utm_medium=paid_search&utm_campaign=g_search_brand&gclid=click-123"}
                """.formatted(eventId, eventAt, receivedAt, identity);

        mockMvc.perform(post("/collect").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isAccepted());
        drainRecomputes();

        Boolean timestampsPreserved = jdbc.queryForObject("""
                SELECT ts_source = 'device'
                   AND event_ts = ?::timestamptz
                   AND received_at = ?::timestamptz
                FROM events WHERE event_id = ?
                """, Boolean.class, eventAt, receivedAt, eventId);
        org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, timestampsPreserved);
        org.junit.jupiter.api.Assertions.assertEquals("google|paid_search|g_search_brand|gclid|paid_google",
                jdbc.queryForObject("""
                        SELECT utm_source || '|' || utm_medium || '|' || utm_campaign || '|' ||
                               click_id_type || '|' || channel
                        FROM sessions WHERE anonymous_id = ?
                        """, String.class, identity));
    }

    @Test
    void lateEventReattributesAnOrderThroughDurableJobs() throws Exception {
        String identity = UUID.randomUUID().toString();
        String emailHash = "late-hash-" + identity;
        String orderId = "late-order-" + identity;
        Instant orderAt = Instant.now().minusSeconds(60);
        Instant clickAt = orderAt.minusSeconds(120);
        String order = """
                {"order_id":"%s","created_at":"%s","updated_at":"%s",
                 "email_hash":"%s","total":500.00,"currency":"INR","financial_status":"paid"}
                """.formatted(orderId, orderAt, orderAt, emailHash);

        mockMvc.perform(post("/webhooks/orders").contentType(MediaType.APPLICATION_JSON).content(order))
                .andExpect(status().isAccepted());
        assertCount("SELECT count(*) FROM order_recompute_queue WHERE order_id = ?", 1, orderId);

        String event = """
                {"event_id":"late-event-%s","event_name":"page_view","timestamp":"%s",
                 "anonymous_id":"%s","email_hash":"%s","utm_source":"google",
                 "utm_campaign":"late-arriving-campaign"}
                """.formatted(identity, clickAt, identity, emailHash);
        mockMvc.perform(post("/collect").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isAccepted());
        drainRecomputes();

        assertCount("""
                SELECT count(*) FROM order_attribution
                WHERE order_id = ? AND model = 'last_click' AND channel = 'paid_google'
                """, 1, orderId);
        assertCount("SELECT count(*) FROM order_recompute_queue WHERE order_id = ?", 0, orderId);
    }

    @Test
    void firstAndLastClickRespectInclusiveSevenDayWindow() throws Exception {
        String identity = UUID.randomUUID().toString();
        String emailHash = "window-hash-" + identity;
        String orderId = "window-order-" + identity;
        Instant orderAt = Instant.parse("2041-03-10T12:00:00Z");

        postEvent("outside-" + identity, identity, emailHash, orderAt.minusSeconds(8 * 86400L),
                orderAt.minusSeconds(8 * 86400L - 60), "tiktok", null);
        postEvent("boundary-" + identity, identity, emailHash, orderAt.minusSeconds(7 * 86400L),
                orderAt.minusSeconds(7 * 86400L - 60), "google", null);
        postEvent("latest-" + identity, identity, emailHash, orderAt.minusSeconds(3600),
                orderAt.minusSeconds(3540), "instagram", null);
        postOrder(orderId, orderAt, orderAt, emailHash, "100.00", "INR", "paid", false)
                .andExpect(status().isAccepted());

        drainRecomputes();

        assertEquals("paid_google", jdbc.queryForObject("""
                SELECT channel FROM order_attribution
                WHERE order_id = ? AND model = 'first_click'
                """, String.class, orderId));
        assertEquals("paid_meta", jdbc.queryForObject("""
                SELECT channel FROM order_attribution
                WHERE order_id = ? AND model = 'last_click'
                """, String.class, orderId));
        assertEquals("click_in_window", jdbc.queryForObject("""
                SELECT reason FROM order_attribution
                WHERE order_id = ? AND model = 'first_click'
                """, String.class, orderId));
        assertEquals(7, jdbc.queryForObject("""
                SELECT window_days FROM order_attribution
                WHERE order_id = ? AND model = 'first_click'
                """, Integer.class, orderId));
    }

    @Test
    void sessionizesAtThirtyMinuteBoundaryAndSplitsAfterIt() throws Exception {
        String identity = UUID.randomUUID().toString();
        Instant base = Instant.parse("2041-04-01T10:00:00Z");
        postEvent("session-1-" + identity, identity, null, base, base.plusSeconds(5), "google", null);
        postEvent("session-2-" + identity, identity, null, base.plusSeconds(1800),
                base.plusSeconds(1805), null, null);
        postEvent("session-3-" + identity, identity, null, base.plusSeconds(3601),
                base.plusSeconds(3606), "instagram", null);

        drainRecomputes();

        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM sessions WHERE anonymous_id = ?", Integer.class, identity));
        assertEquals(2, jdbc.queryForObject("""
                SELECT event_count FROM sessions
                WHERE anonymous_id = ? ORDER BY started_at LIMIT 1
                """, Integer.class, identity));
        assertEquals(1, jdbc.queryForObject("""
                SELECT event_count FROM sessions
                WHERE anonymous_id = ? ORDER BY started_at DESC LIMIT 1
                """, Integer.class, identity));
        assertEquals(3, jdbc.queryForObject("""
                SELECT count(*) FROM events e JOIN sessions s USING (session_id)
                WHERE s.anonymous_id = ?
                """, Integer.class, identity));
    }

    @Test
    void excludesBotAndTestEventsFromSessionsAndAttribution() throws Exception {
        String identity = UUID.randomUUID().toString();
        String emailHash = "excluded-hash-" + identity;
        String orderId = "excluded-order-" + identity;
        Instant orderAt = Instant.parse("2041-05-01T10:00:00Z");

        String excludedEvents = """
                {"event_id":"%s","event_name":"page_view","timestamp":"%s",
                 "received_at":"%s","anonymous_id":"%s","email_hash":"%s",
                 "utm_source":"google","is_bot":true}
                """.formatted("bot-" + identity, orderAt.minusSeconds(120), orderAt.minusSeconds(115),
                identity, emailHash);
        mockMvc.perform(post("/collect").contentType(MediaType.APPLICATION_JSON).content(excludedEvents))
                .andExpect(status().isAccepted());
        String testEvent = """
                {"event_id":"%s","event_name":"page_view","timestamp":"%s",
                 "received_at":"%s","anonymous_id":"%s","email_hash":"%s",
                 "utm_source":"meta","is_test":true}
                """.formatted("test-" + identity, orderAt.minusSeconds(60), orderAt.minusSeconds(55),
                identity, emailHash);
        mockMvc.perform(post("/collect").contentType(MediaType.APPLICATION_JSON).content(testEvent))
                .andExpect(status().isAccepted());
        postOrder(orderId, orderAt, orderAt, emailHash, "45.00", "INR", "paid", false)
                .andExpect(status().isAccepted());

        drainRecomputes();

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM sessions WHERE anonymous_id = ?", Integer.class, identity));
        assertEquals("direct", jdbc.queryForObject("""
                SELECT channel FROM order_attribution
                WHERE order_id = ? AND model = 'last_click'
                """, String.class, orderId));
        assertEquals("no_marketing_touch", jdbc.queryForObject("""
                SELECT reason FROM order_attribution
                WHERE order_id = ? AND model = 'last_click'
                """, String.class, orderId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE anonymous_id = ?", Integer.class, identity));
    }

    @Test
    void mergesIdentitiesAcrossDevicesWhenEmailBecomesKnown() throws Exception {
        String firstVisitor = UUID.randomUUID().toString();
        String secondVisitor = UUID.randomUUID().toString();
        String emailHash = "cross-device-" + UUID.randomUUID();
        Instant at = Instant.parse("2041-06-01T10:00:00Z");

        postEvent("device-a-anon-" + firstVisitor, firstVisitor, null, at, at.plusSeconds(2), null, null);
        postEvent("device-b-" + secondVisitor, secondVisitor, emailHash, at.plusSeconds(600),
                at.plusSeconds(602), "google", null);
        postEvent("device-a-linked-" + firstVisitor, firstVisitor, emailHash, at.plusSeconds(1200),
                at.plusSeconds(1202), "meta", null);

        drainRecomputes();

        Long firstPerson = jdbc.queryForObject("""
                SELECT person_id FROM person_identifiers WHERE id_type = 'anon' AND id_value = ?
                """, Long.class, firstVisitor);
        Long secondPerson = jdbc.queryForObject("""
                SELECT person_id FROM person_identifiers WHERE id_type = 'anon' AND id_value = ?
                """, Long.class, secondVisitor);
        assertNotNull(firstPerson);
        assertEquals(firstPerson, secondPerson);
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM sessions WHERE person_id = ?", Integer.class, firstPerson));
    }

    @Test
    void storesCancelledCsvFinancialStatusInsteadOfUnknown() throws Exception {
        for (String inputStatus : new String[] {"cancelled", "canceled"}) {
            String orderId = "cancelled-order-" + UUID.randomUUID();
            Instant createdAt = Instant.parse("2041-07-01T10:00:00Z");

            postOrder(orderId, createdAt, createdAt, null, "900.00", "INR", inputStatus, false)
                    .andExpect(status().isAccepted());

            assertEquals("cancelled", jdbc.queryForObject(
                    "SELECT financial_status FROM orders WHERE order_id = ?", String.class, orderId));
            assertEquals("cancelled", jdbc.queryForObject(
                    "SELECT financial_status FROM order_webhooks WHERE order_id = ?", String.class, orderId));
        }
    }

    @Test
    void appliesOnlyNewestWebhookStateAndDeduplicatesWebhookVersions() throws Exception {
        String orderId = "webhook-order-" + UUID.randomUUID();
        Instant createdAt = Instant.parse("2041-07-01T10:00:00Z");
        Instant newest = createdAt.plusSeconds(120);

        postOrder(orderId, createdAt, newest, null, "900.00", "INR", "paid", false)
                .andExpect(status().isAccepted());
        postOrder(orderId, createdAt, newest, null, "900.00", "INR", "paid", false)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("duplicate")));
        postOrder(orderId, createdAt, createdAt.plusSeconds(60), null,
                "1.00", "INR", "refunded", false).andExpect(status().isAccepted());

        drainRecomputes();

        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM order_webhooks WHERE order_id = ?", Integer.class, orderId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT webhook_count FROM orders WHERE order_id = ?", Integer.class, orderId));
        assertEquals(new BigDecimal("900.00"), jdbc.queryForObject(
                "SELECT total FROM orders WHERE order_id = ?", BigDecimal.class, orderId));
        assertEquals("paid", jdbc.queryForObject(
                "SELECT financial_status FROM orders WHERE order_id = ?", String.class, orderId));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM order_attribution
                WHERE order_id = ? AND counted AND revenue_inr = 900.00
                """, Integer.class, orderId));
    }

    @Test
    void unlinkedOrderIsUnattributedButPaidRevenueRemainsCounted() throws Exception {
        String orderId = "unlinked-" + UUID.randomUUID();
        Instant orderAt = Instant.parse("2041-08-01T10:00:00Z");
        postOrder(orderId, orderAt, orderAt, null, "50.00", "INR", "paid", false)
                .andExpect(status().isAccepted());

        drainRecomputes();

        assertEquals("unattributed", jdbc.queryForObject("""
                SELECT channel FROM order_attribution
                WHERE order_id = ? AND model = 'last_click'
                """, String.class, orderId));
        assertEquals("none", jdbc.queryForObject("""
                SELECT link_method FROM order_attribution
                WHERE order_id = ? AND model = 'last_click'
                """, String.class, orderId));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
                SELECT counted AND revenue_inr = 50.00
                FROM order_attribution WHERE order_id = ? AND model = 'last_click'
                """, Boolean.class, orderId));
    }

    @Test
    void adSpendRetriesAreIdempotentAndLatestSnapshotFeedsReports() throws Exception {
        LocalDate day = LocalDate.parse("2041-09-14");
        String campaign = "snapshot-" + UUID.randomUUID();
        String firstPulledAt = "2041-09-15T00:00:00Z";
        String latestPulledAt = "2041-09-16T00:00:00Z";
        String first = """
                {"spend_date":"%s","platform":"instagram","campaign":"%s",
                 "spend":100.00,"currency":"INR","pulled_at":"%s"}
                """.formatted(day, campaign, firstPulledAt);
        mockMvc.perform(post("/ad-spend").contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/ad-spend").contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("duplicate")));
        String latest = """
                {"spend_date":"%s","platform":"meta","campaign":"%s",
                 "spend":250.00,"currency":"INR","pulled_at":"%s"}
                """.formatted(day, campaign, latestPulledAt);
        mockMvc.perform(post("/ad-spend").contentType(MediaType.APPLICATION_JSON).content(latest))
                .andExpect(status().isAccepted());

        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM ad_spend_pulls WHERE spend_date = ? AND campaign = ?
                """, Integer.class, day, campaign));
        assertEquals(new BigDecimal("250.00"), jdbc.queryForObject("""
                SELECT spend_inr FROM ad_spend_current
                WHERE spend_date = ? AND campaign = ?
                """, BigDecimal.class, day, campaign));
        mockMvc.perform(get("/api/reports/channels")
                        .param("from", day.toString()).param("to", day.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"spendInr\":250.00")));
    }

    @Test
    void fallsBackToReceivedTimestampAndRecordsInvalidRequests() throws Exception {
        String identity = UUID.randomUUID().toString();
        String eventId = "fallback-" + identity;
        Instant receivedAt = Instant.parse("2041-10-01T10:00:00Z");
        String staleEvent = """
                {"event_id":"%s","event_name":"page_view",
                 "timestamp":"2041-09-20T10:00:00Z","received_at":"%s",
                 "anonymous_id":"%s"}
                """.formatted(eventId, receivedAt, identity);
        mockMvc.perform(post("/collect").contentType(MediaType.APPLICATION_JSON).content(staleEvent))
                .andExpect(status().isAccepted());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
                SELECT ts_source = 'received_at' AND event_ts = ?::timestamptz
                FROM events WHERE event_id = ?
                """, Boolean.class, receivedAt.toString(), eventId));

        String badTimestamp = """
                {"event_id":"bad-%s","event_name":"page_view","anonymous_id":"%s",
                 "received_at":"not-a-timestamp"}
                """.formatted(identity, identity);
        mockMvc.perform(post("/collect")
                        .header("X-Request-Id", "bad-timestamp-" + identity)
                        .contentType(MediaType.APPLICATION_JSON).content(badTimestamp))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("received_at")));
        assertCount("SELECT count(*) FROM rejects WHERE payload ->> 'request_id' = ?",
                1, "bad-timestamp-" + identity);
        assertCount("SELECT count(*) FROM events WHERE event_id = ?", 0, "bad-" + identity);
    }

    @Test
    void retryBackoffRemainsVisibleInQueueStatus() throws Exception {
        String identity = UUID.randomUUID().toString();
        postEvent("retry-" + identity, identity, null, Instant.now(), Instant.now(), null, null);
        Long personId = jdbc.queryForObject("""
                SELECT person_id FROM person_identifiers WHERE id_type = 'anon' AND id_value = ?
                """, Long.class, identity);
        repository.enqueuePersonRecompute(personId, "retry-test");
        repository.recordRecomputeFailure("session", identity, new IllegalStateException("test failure"));
        repository.recordRecomputeFailure("person", personId.toString(),
                new IllegalStateException("test failure"));

        assertEquals(1, jdbc.queryForObject("""
                SELECT attempt_count FROM session_recompute_queue WHERE anonymous_id = ?
                """, Integer.class, identity));
        assertEquals(1, jdbc.queryForObject("""
                SELECT attempt_count FROM person_recompute_queue WHERE person_id = ?
                """, Integer.class, personId));
        mockMvc.perform(get("/api/processing/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingSessions").value(1))
                .andExpect(jsonPath("$.pendingPeople").value(1))
                .andExpect(jsonPath("$.retryingJobs").value(2));
    }

    @Test
    void rejectsUnsupportedCurrencyAndInvalidDateRange() throws Exception {
        String identity = UUID.randomUUID().toString();
        String badCurrency = """
                {"event_id":"currency-%s","event_name":"page_view","anonymous_id":"%s",
                 "currency":"ZZZ"}
                """.formatted(identity, identity);
        String invalidCurrencyRequestId = "currency-request-" + identity;
        String invalidRangeRequestId = "range-request-" + identity;
        mockMvc.perform(post("/collect").header("X-Request-Id", invalidCurrencyRequestId)
                        .contentType(MediaType.APPLICATION_JSON).content(badCurrency))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Unsupported currency")));
        mockMvc.perform(get("/api/reports/channels").header("X-Request-Id", invalidRangeRequestId)
                        .param("from", "2041-12-02").param("to", "2041-12-01"))
                .andExpect(status().isBadRequest());
        assertCount("SELECT count(*) FROM events WHERE event_id = ?", 0, "currency-" + identity);
        assertCount("SELECT count(*) FROM rejects WHERE payload ->> 'request_id' IN (?, ?)",
                2, invalidCurrencyRequestId, invalidRangeRequestId);
    }

    @Test
    void rejectsUnsupportedAttributionModel() throws Exception {
        mockMvc.perform(get("/api/reports/channels")
                        .param("from", LocalDate.now(ZoneOffset.UTC).toString())
                        .param("to", LocalDate.now(ZoneOffset.UTC).toString())
                        .param("model", "linear"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exposesRecomputeBacklogStatus() throws Exception {
        mockMvc.perform(get("/api/processing/status"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("pendingSessions")))
                .andExpect(content().string(containsString("retryingJobs")));
    }

    @Test
    void exposesAvailableReportingDateRange() throws Exception {
        mockMvc.perform(get("/api/reports/data-range"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"from\"")))
                .andExpect(content().string(containsString("\"to\"")));
    }

    @Test
    void validatesRequestDtoAndReturnsCorrelationId() throws Exception {
        mockMvc.perform(post("/collect")
                        .header("X-Request-Id", "test-request-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Request-Id", "test-request-123"))
                .andExpect(content().string(containsString("Request validation failed")));
        assertCount("SELECT count(*) FROM rejects WHERE payload ->> 'request_id' = ?", 1,
                "test-request-123");
    }

    private void assertCount(String query, int expected, Object... parameters) {
        Integer actual = jdbc.queryForObject(query, Integer.class, parameters);
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, query);
    }

    private org.springframework.test.web.servlet.ResultActions postEvent(
            String eventId, String anonymousId, String emailHash, Instant timestamp, Instant receivedAt,
            String source, String orderId) throws Exception {
        String body = """
                {"event_id":"%s","event_name":"page_view","timestamp":"%s",
                 "received_at":"%s","anonymous_id":"%s","email_hash":%s,
                 "utm_source":%s,"order_id":%s}
                """.formatted(eventId, timestamp, receivedAt, anonymousId, jsonString(emailHash),
                jsonString(source), jsonString(orderId));
        return mockMvc.perform(post("/collect").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private org.springframework.test.web.servlet.ResultActions postOrder(
            String orderId, Instant createdAt, Instant updatedAt, String emailHash,
            String total, String currency, String financialStatus, boolean test) throws Exception {
        String body = """
                {"order_id":"%s","created_at":"%s","updated_at":"%s",
                 "email_hash":%s,"total":%s,"currency":"%s",
                 "financial_status":"%s","is_test":%s}
                """.formatted(orderId, createdAt, updatedAt, jsonString(emailHash), total, currency,
                financialStatus, test);
        return mockMvc.perform(post("/webhooks/orders").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String jsonString(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void drainRecomputes() {
        for (int attempts = 0; attempts < 1000; attempts++) {
            boolean didWork = recomputeProcessor.processNextSessionJob();
            didWork |= recomputeProcessor.processNextPersonJob();
            didWork |= recomputeProcessor.processNextOrderJob();
            if (!didWork) return;
        }
        throw new AssertionError("Recompute queues did not drain");
    }
}
