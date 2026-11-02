package com.attributioniq.attribution_iq.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum IngestionStatus {
    ACCEPTED("accepted"), DUPLICATE("duplicate");

    private final String value;

    IngestionStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
