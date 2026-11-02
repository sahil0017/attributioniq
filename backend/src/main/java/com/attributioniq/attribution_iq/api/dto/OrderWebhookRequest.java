package com.attributioniq.attribution_iq.api.dto;

import com.attributioniq.attribution_iq.domain.FinancialStatus;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record OrderWebhookRequest(
        @JsonProperty("order_id") @JsonAlias({"orderId", "id"}) @NotBlank String orderId,
        @JsonProperty("created_at") @JsonAlias({"createdAt", "processed_at", "processedAt"}) @NotBlank String createdAt,
        @JsonProperty("updated_at") @JsonAlias({"updatedAt", "modified_at", "modifiedAt"}) String updatedAt,
        @JsonProperty("email_hash") @JsonAlias("emailHash") String emailHash,
        @JsonProperty("total") @JsonAlias({"amount", "current_total_price", "currentTotalPrice"}) @NotNull @PositiveOrZero BigDecimal total,
        @JsonProperty("currency") @JsonAlias({"currency_code", "currencyCode"}) @NotBlank String currency,
        @JsonProperty("financial_status") @JsonAlias({"financialStatus", "status"}) @NotNull FinancialStatus financialStatus,
        @JsonProperty("is_test") @JsonAlias({"isTest", "test"}) Boolean test
) { }
