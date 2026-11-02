package com.attributioniq.attribution_iq.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum FinancialStatus {
    PAID("paid"), PENDING("pending"), AUTHORIZED("authorized"), PARTIALLY_PAID("partially_paid"),
    PARTIALLY_REFUNDED("partially_refunded"), REFUNDED("refunded"), VOIDED("voided"),
    CANCELLED("cancelled"), UNKNOWN("unknown");

    private final String value;

    FinancialStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public boolean isPaid() {
        return this == PAID;
    }

    @JsonCreator
    public static FinancialStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Financial status is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (FinancialStatus status : values()) {
            if (status.value.equals(normalized)) {
                return status;
            }
        }
        if ("cancelled".equals(normalized)) {
            return CANCELLED;
        }
        return UNKNOWN;
    }
}
