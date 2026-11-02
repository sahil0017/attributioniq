package com.attributioniq.attribution_iq.api.dto;

import com.attributioniq.attribution_iq.domain.AdPlatform;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AdSpendRequest(
        @JsonProperty("spend_date") @JsonAlias({"date", "day"}) @NotNull LocalDate spendDate,
        @NotNull AdPlatform platform,
        @JsonAlias({"campaign_name", "campaignName"}) @NotBlank String campaign,
        @NotNull @PositiveOrZero BigDecimal spend,
        @NotBlank String currency,
        @JsonProperty("pulled_at") @JsonAlias({"pulledAt", "updated_at", "updatedAt"}) String pulledAt
) { }
