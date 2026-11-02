package com.attributioniq.attribution_iq.api.dto;

import java.time.Instant;

public record ProcessingStatusResponse(long pendingSessions, long pendingPeople, long pendingOrders,
                                       long retryingJobs, Instant oldestEnqueuedAt,
                                       Instant lastDataReceivedAt, String lastDataReceivedBy) { }
