package com.attributioniq.attribution_iq.domain;

public enum AttributionReason {
    CLICK_IN_WINDOW("click_in_window"), NO_MARKETING_TOUCH("no_marketing_touch"), NO_PERSON_LINK("no_person_link");

    private final String value;

    AttributionReason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
