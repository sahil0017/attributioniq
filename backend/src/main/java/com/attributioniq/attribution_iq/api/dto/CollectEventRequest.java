package com.attributioniq.attribution_iq.api.dto;

import com.attributioniq.attribution_iq.domain.ClickIdType;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record CollectEventRequest(
        @JsonProperty("event_id") @JsonAlias("eventId") @NotBlank String eventId,
        @JsonProperty("event_name") @JsonAlias({"eventName", "name"}) @NotBlank String eventName,
        @JsonProperty("anonymous_id") @JsonAlias({"anonymousId", "visitor_id", "visitorId"}) @NotBlank String anonymousId,
        @JsonProperty("ts_raw") @JsonAlias({"ts_device", "event_ts", "timestamp", "event_time", "ts", "occurred_at"}) String timestamp,
        @JsonProperty("received_at") @JsonAlias("receivedAt") String receivedAt,
        @JsonProperty("email_hash") @JsonAlias("emailHash") String emailHash,
        @JsonProperty("page_url") @JsonAlias({"pageUrl", "url"}) String pageUrl,
        @JsonAlias({"referrer_url", "referrerUrl"}) String referrer,
        @JsonProperty("referrer_host") @JsonAlias("referrerHost") String referrerHost,
        @JsonProperty("user_agent") @JsonAlias("userAgent") String userAgent,
        @JsonProperty("ip_address") @JsonAlias({"ip", "ipAddress"}) String ip,
        @PositiveOrZero BigDecimal value,
        String currency,
        @JsonProperty("order_id") @JsonAlias("orderId") String orderId,
        @JsonProperty("utm_source") @JsonAlias({"utmSource", "source"}) String utmSource,
        @JsonProperty("utm_medium") @JsonAlias({"utmMedium", "medium"}) String utmMedium,
        @JsonProperty("utm_campaign") @JsonAlias({"utmCampaign", "campaign"}) String utmCampaign,
        @JsonProperty("click_id_type") @JsonAlias("clickIdType") ClickIdType clickIdType,
        @JsonProperty("click_id") @JsonAlias("clickId") String clickId,
        String gclid,
        String fbclid,
        String ttclid,
        String msclkid,
        @JsonProperty("is_bot") @JsonAlias("isBot") Boolean bot,
        @JsonProperty("is_test") @JsonAlias("isTest") Boolean test
) { }
