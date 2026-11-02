package com.attributioniq.attribution_iq.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.Optional;

public enum ClickIdType {
    GCLID("gclid"), FBCLID("fbclid"), TTCLID("ttclid"), MSCLKID("msclkid");

    private final String value;

    ClickIdType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ClickIdType fromValue(String value) {
        return find(value).orElseThrow(() -> new IllegalArgumentException("Unsupported click ID type: " + value));
    }

    public static Optional<ClickIdType> find(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ClickIdType type : values()) {
            if (type.value.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
