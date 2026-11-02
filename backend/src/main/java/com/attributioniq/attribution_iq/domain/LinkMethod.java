package com.attributioniq.attribution_iq.domain;

public enum LinkMethod {
    EMAIL_HASH("email_hash"), PURCHASE_EVENT("purchase_event"), NONE("none");

    private final String value;

    LinkMethod(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
