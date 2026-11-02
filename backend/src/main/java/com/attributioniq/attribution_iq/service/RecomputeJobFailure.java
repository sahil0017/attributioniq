package com.attributioniq.attribution_iq.service;

final class RecomputeJobFailure extends RuntimeException {
    private final String queue;
    private final String key;

    RecomputeJobFailure(String queue, String key, RuntimeException cause) {
        super("Failed " + queue + " recompute for " + key, cause);
        this.queue = queue;
        this.key = key;
    }

    String queue() {
        return queue;
    }

    String key() {
        return key;
    }
}
