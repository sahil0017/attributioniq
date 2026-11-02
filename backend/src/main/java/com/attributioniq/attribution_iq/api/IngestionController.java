package com.attributioniq.attribution_iq.api;

import com.attributioniq.attribution_iq.api.dto.AdSpendRequest;
import com.attributioniq.attribution_iq.api.dto.CollectEventRequest;
import com.attributioniq.attribution_iq.api.dto.OrderWebhookRequest;
import com.attributioniq.attribution_iq.service.AttributionPipelineService;
import com.attributioniq.attribution_iq.domain.IngestionResult;
import com.attributioniq.attribution_iq.domain.IngestionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP boundary for incoming source records. Validation is performed before
 * requests are passed to the transactional pipeline service.
 */
@RestController
@Tag(name = "Ingestion", description = "Website events, order webhooks, and ad spend")
public class IngestionController {
    private final AttributionPipelineService pipeline;

    public IngestionController(AttributionPipelineService pipeline) {
        this.pipeline = pipeline;
    }

    /** Returns 202 for a newly accepted event and 200 for a duplicate delivery. */
    @PostMapping(ApiPaths.COLLECT)
    @Operation(summary = "Collect a website event",
            description = "Idempotently stores an event and queues session and attribution recomputation.")
    public ResponseEntity<IngestionResult> collect(@Valid @RequestBody CollectEventRequest event) {
        return response(pipeline.collect(event));
    }

    /** Stores order history and queues work; attribution may finish after this response. */
    @PostMapping(ApiPaths.ORDER_WEBHOOKS)
    @Operation(summary = "Receive an order webhook",
            description = "Stores the order version and queues attribution recomputation.")
    public ResponseEntity<IngestionResult> orderWebhook(@Valid @RequestBody OrderWebhookRequest webhook) {
        return response(pipeline.receiveOrder(webhook));
    }

    /** Returns 202 for a new snapshot and 200 when the snapshot already exists. */
    @PostMapping(ApiPaths.AD_SPEND)
    @Operation(summary = "Receive an ad-spend snapshot",
            description = "Stores an idempotent spend snapshot for reporting.")
    public ResponseEntity<IngestionResult> adSpend(@Valid @RequestBody AdSpendRequest spend) {
        return response(pipeline.receiveSpend(spend));
    }

    private static ResponseEntity<IngestionResult> response(IngestionResult result) {
        return ResponseEntity.status(result.status() == IngestionStatus.ACCEPTED ? 202 : 200).body(result);
    }
}
