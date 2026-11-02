package com.attributioniq.attribution_iq.service;

import com.attributioniq.attribution_iq.database.AttributionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled queue drainer. Jobs are claimed through repository methods using
 * PostgreSQL row locking, and failures are recorded for delayed retry.
 */
@Component
public class AttributionRecomputeWorker {
    private static final Logger log = LoggerFactory.getLogger(AttributionRecomputeWorker.class);

    private final AttributionRecomputeProcessor processor;
    private final AttributionRepository repository;
    private final int maxJobsPerCycle;

    public AttributionRecomputeWorker(AttributionRecomputeProcessor processor,
                                     AttributionRepository repository,
                                     @Value("${attribution.recompute.max-jobs-per-cycle:50}") int maxJobsPerCycle) {
        this.processor = processor;
        this.repository = repository;
        if (maxJobsPerCycle < 1) {
            throw new IllegalArgumentException("attribution.recompute.max-jobs-per-cycle must be positive");
        }
        this.maxJobsPerCycle = maxJobsPerCycle;
    }

    /** Processes up to the configured job budget, rotating across queue types. */
    @Scheduled(fixedDelayString = "${attribution.recompute.fixed-delay-ms:100}")
    public void processPendingJobs() {
        int processed = 0;
        while (processed < maxJobsPerCycle) {
            boolean didWork = false;
            if (processOne(processor::processNextSessionJob)) {
                processed++;
                didWork = true;
            }
            if (processed < maxJobsPerCycle && processOne(processor::processNextPersonJob)) {
                processed++;
                didWork = true;
            }
            if (processed < maxJobsPerCycle && processOne(processor::processNextOrderJob)) {
                processed++;
                didWork = true;
            }
            if (!didWork) return;
        }
    }

    private boolean processOne(JobProcessor job) {
        try {
            return job.process();
        } catch (RecomputeJobFailure failure) {
            repository.recordRecomputeFailure(failure.queue(), failure.key(), failure.getCause());
            log.error("Recompute job failed queue={} key={}; retry scheduled",
                    failure.queue(), failure.key(), failure.getCause());
            return true;
        } catch (RuntimeException exception) {
            log.error("Unable to claim recompute job", exception);
            return false;
        }
    }

    @FunctionalInterface
    private interface JobProcessor {
        boolean process();
    }
}
