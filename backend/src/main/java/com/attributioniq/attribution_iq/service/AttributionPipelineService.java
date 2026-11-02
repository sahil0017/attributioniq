package com.attributioniq.attribution_iq.service;

import com.attributioniq.attribution_iq.database.AttributionRepository;
import com.attributioniq.attribution_iq.database.AttributionRepository.EventData;
import com.attributioniq.attribution_iq.database.AttributionRepository.OrderData;
import com.attributioniq.attribution_iq.database.AttributionRepository.SpendData;
import com.attributioniq.attribution_iq.api.dto.AdSpendRequest;
import com.attributioniq.attribution_iq.api.dto.CollectEventRequest;
import com.attributioniq.attribution_iq.api.dto.OrderWebhookRequest;
import com.attributioniq.attribution_iq.domain.AdPlatform;
import com.attributioniq.attribution_iq.domain.ClickIdType;
import com.attributioniq.attribution_iq.domain.EventTimestampSource;
import com.attributioniq.attribution_iq.domain.IngestionResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

/**
 * Validates and normalizes incoming records, persists raw facts idempotently,
 * links identities, and enqueues derived work in the same transaction.
 */
@Service
public class AttributionPipelineService {
    private static final Logger log = LoggerFactory.getLogger(AttributionPipelineService.class);

    private final AttributionRepository repository;

    public AttributionPipelineService(AttributionRepository repository) {
        this.repository = repository;
    }

    /**
     * Accepts a website event. Invalid device timestamps fall back to receive
     * time; duplicate event IDs are acknowledged without creating new work.
     */
    @Transactional
    public IngestionResult collect(CollectEventRequest input) {
        String rawTimestampValue = input.timestamp() == null ? "" : input.timestamp();
        AttributionRepository.PipelineSettings settings = repository.loadPipelineSettings();
        Instant receivedAt = parseInstant(input.receivedAt());
        if (input.receivedAt() != null && receivedAt == null) {
            throw badRequest("received_at must be an ISO-8601 timestamp");
        }
        if (receivedAt == null) receivedAt = Instant.now();
        Instant deviceTimestamp = parseInstant(rawTimestampValue);
        boolean useDeviceTimestamp = deviceTimestamp != null
                && !deviceTimestamp.isBefore(receivedAt.minus(settings.maxDeviceTimestampAge()))
                && !deviceTimestamp.isAfter(receivedAt.plus(settings.maxDeviceTimestampFutureSkew()));
        EventTimestampSource timestampSource = useDeviceTimestamp
                ? EventTimestampSource.DEVICE : EventTimestampSource.RECEIVED_AT;

        String emailHash = normalized(input.emailHash());
        String referrerHost = normalizeHost(input.referrerHost(), input.referrer());
        Map<String, String> pageParameters = queryParameters(input.pageUrl());
        String utmSource = normalized(firstValue(input.utmSource(), pageParameters.get("utm_source")));
        String utmMedium = normalized(firstValue(input.utmMedium(), pageParameters.get("utm_medium")));
        String utmCampaign = firstValue(input.utmCampaign(), pageParameters.get("utm_campaign"));
        ClickIdType clickIdType = clickIdType(input);
        if (clickIdType == null) clickIdType = clickIdType(pageParameters);
        String clickId = clickId(input, clickIdType);
        if (clickId == null && clickIdType != null) clickId = pageParameters.get(clickIdType.value());
        String currency = normalizeCurrency(input.currency());
        validateCurrency(currency);

        EventData event = new EventData(input.eventId(), input.eventName(), rawTimestampValue,
                useDeviceTimestamp ? deviceTimestamp : null, receivedAt,
                useDeviceTimestamp ? deviceTimestamp : receivedAt, timestampSource, input.anonymousId(), emailHash,
                input.pageUrl(), input.referrer(), referrerHost, input.userAgent(), input.ip(), input.value(),
                currency, input.orderId(), utmSource, utmMedium, normalized(utmCampaign),
                clickIdType == null ? null : clickIdType.value(), clickId,
                Boolean.TRUE.equals(input.bot()), Boolean.TRUE.equals(input.test()));

        if (!repository.insertEvent(event)) {
            repository.incrementDuplicateEvent(input.eventId());
            log.info("Event ingestion deduplicated event_id={}", input.eventId());
            return IngestionResult.duplicate(input.eventId());
        }

        Long personId = repository.linkIdentity(input.anonymousId(), emailHash);
        repository.enqueueSessionRecompute(input.anonymousId(), "event_ingested");
        log.info("Event accepted event_id={} person_id={} recompute_queued=true", input.eventId(), personId);
        return IngestionResult.accepted(input.eventId());
    }

    /**
     * Persists order webhook history and updates current state only for a newer
     * version. Queues person-level or order-level attribution recomputation.
     */
    @Transactional
    public IngestionResult receiveOrder(OrderWebhookRequest input) {
        String orderId = input.orderId();
        Instant createdAt = parseRequiredInstant(input.createdAt());
        Instant updatedAt = parseInstant(input.updatedAt());
        if (updatedAt == null) updatedAt = createdAt;
        if (updatedAt.isBefore(createdAt)) throw badRequest("updated_at must not be before created_at");

        String emailHash = normalized(input.emailHash());
        BigDecimal total = input.total();
        String currency = normalizeCurrency(input.currency());
        validateCurrency(currency);
        OrderData order = new OrderData(orderId, createdAt, updatedAt, emailHash, total, currency,
                input.financialStatus().value(), Boolean.TRUE.equals(input.test()), Instant.now());
        if (!repository.recordOrderWebhook(order)) {
            log.info("Order webhook deduplicated order_id={}", orderId);
            return IngestionResult.duplicate(orderId);
        }

        Long personId = repository.findPerson("email", emailHash);
        if (personId == null) personId = repository.findPersonForOrderEvent(orderId);
        if (personId == null) repository.enqueueOrderRecompute(orderId, "order_received");
        else repository.enqueuePersonRecompute(personId, "order_updated");
        log.info("Order webhook accepted order_id={} status={} recompute_queued=true",
                orderId, input.financialStatus().value());
        return IngestionResult.accepted(orderId);
    }

    /** Stores an append-only ad-spend snapshot; the reporting view selects the latest. */
    @Transactional
    public IngestionResult receiveSpend(AdSpendRequest input) {
        AdPlatform platform = input.platform();
        String campaign = input.campaign();
        String currency = normalizeCurrency(input.currency());
        validateCurrency(currency);
        Instant pulledAt = parseInstant(input.pulledAt());
        if (pulledAt == null) pulledAt = Instant.now();

        SpendData data = new SpendData(input.spendDate(), platform.value(), campaign, input.spend(), currency, pulledAt);
        String receiptId = input.spendDate() + "/" + platform.value() + "/" + campaign;
        boolean inserted = repository.insertSpend(data);
        log.info("Ad spend snapshot {} platform={} date={}", inserted ? "stored" : "deduplicated",
                platform.value(), input.spendDate());
        return inserted ? IngestionResult.accepted(receiptId) : IngestionResult.duplicate(receiptId);
    }

    private void validateCurrency(String currency) {
        if (currency != null && !repository.currencyExists(currency)) {
            throw badRequest("Unsupported currency: " + currency);
        }
    }

    private static ClickIdType clickIdType(CollectEventRequest input) {
        if (input.clickIdType() != null) return input.clickIdType();
        if (input.gclid() != null) return ClickIdType.GCLID;
        if (input.fbclid() != null) return ClickIdType.FBCLID;
        if (input.ttclid() != null) return ClickIdType.TTCLID;
        if (input.msclkid() != null) return ClickIdType.MSCLKID;
        return null;
    }

    private static String clickId(CollectEventRequest input, ClickIdType type) {
        if (type == null) return null;
        if (input.clickId() != null) return input.clickId();
        return switch (type) {
            case GCLID -> input.gclid();
            case FBCLID -> input.fbclid();
            case TTCLID -> input.ttclid();
            case MSCLKID -> input.msclkid();
        };
    }

    private static ClickIdType clickIdType(Map<String, String> parameters) {
        for (ClickIdType type : ClickIdType.values()) {
            if (StringUtils.hasText(parameters.get(type.value()))) return type;
        }
        return null;
    }

    private static String firstValue(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private static Map<String, String> queryParameters(String pageUrl) {
        if (!StringUtils.hasText(pageUrl)) return Map.of();
        try {
            String query = new URI(pageUrl).getRawQuery();
            if (query == null) return Map.of();
            Map<String, String> parameters = new java.util.LinkedHashMap<>();
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                parameters.putIfAbsent(key.toLowerCase(Locale.ROOT), value);
            }
            return parameters;
        } catch (IllegalArgumentException | URISyntaxException exception) {
            return Map.of();
        }
    }

    private static String normalizeCurrency(String currency) {
        return currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String normalized(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Instant parseRequiredInstant(String value) {
        Instant instant = parseInstant(value);
        if (instant == null) throw badRequest("created_at must be an ISO-8601 timestamp");
        return instant;
    }

    private static Instant parseInstant(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignoredOffset) {
                try {
                    return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException ignoredLocal) {
                    return null;
                }
            }
        }
    }

    private static String normalizeHost(String explicitHost, String referrer) {
        String host = explicitHost == null ? referrer : explicitHost;
        if (host == null) return null;
        try {
            URI uri = new URI(host.contains("://") ? host : "https://" + host);
            host = uri.getHost();
        } catch (URISyntaxException exception) {
            return null;
        }
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT).trim();
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
    }
}
