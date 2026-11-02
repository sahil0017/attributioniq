package com.attributioniq.attribution_iq.domain;

public enum EventTimestampSource {
    DEVICE("device"), RECEIVED_AT("received_at");

    private final String value;

    EventTimestampSource(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
