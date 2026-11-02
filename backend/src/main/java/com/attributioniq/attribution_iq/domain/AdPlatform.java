package com.attributioniq.attribution_iq.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum AdPlatform {
    META("meta"), GOOGLE("google"), TIKTOK("tiktok");

    private final String value;

    AdPlatform(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static AdPlatform fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Platform is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("facebook") || normalized.equals("instagram")) {
            normalized = "meta";
        }
        for (AdPlatform platform : values()) {
            if (platform.value.equals(normalized)) {
                return platform;
            }
        }
        throw new IllegalArgumentException("Unsupported platform: " + value);
    }
}
