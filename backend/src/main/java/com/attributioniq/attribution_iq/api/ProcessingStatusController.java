package com.attributioniq.attribution_iq.api;

import com.attributioniq.attribution_iq.api.dto.ProcessingStatusResponse;
import com.attributioniq.attribution_iq.database.AttributionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Provides operational queue depth and latest-ingestion metadata for clients. */
@RestController
@Tag(name = "Operations", description = "Ingestion freshness and recompute queue status")
public class ProcessingStatusController {
    private final AttributionRepository repository;

    public ProcessingStatusController(AttributionRepository repository) {
        this.repository = repository;
    }

    /** Reads queue counts and latest accepted input metadata from PostgreSQL. */
    @GetMapping(ApiPaths.PROCESSING_STATUS)
    @Operation(summary = "Get ingestion freshness and recompute backlog")
    public ProcessingStatusResponse status() {
        AttributionRepository.RecomputeQueueStatus status = repository.recomputeQueueStatus();
        return new ProcessingStatusResponse(status.sessionPending(), status.personPending(),
                status.orderPending(), status.retrying(), status.oldestEnqueuedAt(),
                status.lastDataReceivedAt(), status.lastDataReceivedBy());
    }
}
