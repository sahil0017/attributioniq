package com.attributioniq.attribution_iq.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;

public enum AttributionModel {
    LAST_CLICK("last_click"),
    FIRST_CLICK("first_click");

    private final String value;

    AttributionModel(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static AttributionModel fromValue(String value) {
        if (value == null || value.isBlank()) {
            return LAST_CLICK;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(model -> model.value.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported attribution model: " + value));
    }
}
