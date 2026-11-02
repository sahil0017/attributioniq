package com.attributioniq.attribution_iq.domain;

public record IngestionResult(IngestionStatus status, String id) {
    public static IngestionResult accepted(String id) {
        return new IngestionResult(IngestionStatus.ACCEPTED, id);
    }

    public static IngestionResult duplicate(String id) {
        return new IngestionResult(IngestionStatus.DUPLICATE, id);
    }
}
