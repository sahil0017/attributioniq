package com.attributioniq.attribution_iq.service;

import com.attributioniq.attribution_iq.config.AttributionConstants;
import com.attributioniq.attribution_iq.database.AttributionRepository;
import com.attributioniq.attribution_iq.database.AttributionRepository.AttributionData;
import com.attributioniq.attribution_iq.database.AttributionRepository.EventSessionLink;
import com.attributioniq.attribution_iq.database.AttributionRepository.OrderData;
import com.attributioniq.attribution_iq.database.AttributionRepository.SessionData;
import com.attributioniq.attribution_iq.database.AttributionRepository.SessionEventData;
import com.attributioniq.attribution_iq.database.AttributionRepository.TouchData;
import com.attributioniq.attribution_iq.domain.AttributionModel;
import com.attributioniq.attribution_iq.domain.AttributionReason;
import com.attributioniq.attribution_iq.domain.FinancialStatus;
import com.attributioniq.attribution_iq.domain.LinkMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes individual durable recompute jobs transactionally. A failed
 * transaction leaves the job available for the worker's retry policy.
 */
@Service
public class AttributionRecomputeProcessor {
    private final AttributionRepository repository;

    public AttributionRecomputeProcessor(AttributionRepository repository) {
        this.repository = repository;
    }

    /** Rebuilds one visitor's sessions and queues attribution refresh for its linked person. */
    @Transactional
    public boolean processNextSessionJob() {
        String anonymousId = repository.claimSessionRecompute();
        if (anonymousId == null) return false;
        try {
            AttributionRepository.PipelineSettings settings = repository.loadPipelineSettings();
            rebuildSessions(anonymousId, repository.findPerson("anon", anonymousId), settings);
            repository.completeSessionRecompute(anonymousId);
            Long personId = repository.findPerson("anon", anonymousId);
            if (personId != null) repository.enqueuePersonRecompute(personId, "journey_rebuilt");
            return true;
        } catch (RuntimeException exception) {
            throw new RecomputeJobFailure("session", anonymousId, exception);
        }
    }

    /** Recomputes both attribution models for orders linked to the next person job. */
    @Transactional
    public boolean processNextPersonJob() {
        Long personId = repository.claimPersonRecompute();
        if (personId == null) return false;
        try {
            AttributionRepository.PipelineSettings settings = repository.loadPipelineSettings();
            for (String orderId : repository.findOrderIdsForPerson(personId)) {
                recomputeAttributionForOrder(orderId, settings);
            }
            repository.completePersonRecompute(personId);
            return true;
        } catch (RuntimeException exception) {
            throw new RecomputeJobFailure("person", personId.toString(), exception);
        }
    }

    /** Recomputes both attribution models for the next order awaiting identity linkage. */
    @Transactional
    public boolean processNextOrderJob() {
        String orderId = repository.claimOrderRecompute();
        if (orderId == null) return false;
        try {
            recomputeAttributionForOrder(orderId, repository.loadPipelineSettings());
            repository.completeOrderRecompute(orderId);
            return true;
        } catch (RuntimeException exception) {
            throw new RecomputeJobFailure("order", orderId, exception);
        }
    }

    private void rebuildSessions(String anonymousId, Long personId,
                                 AttributionRepository.PipelineSettings settings) {
        repository.deleteSessions(anonymousId);
        List<SessionEventData> events = repository.findSessionEvents(anonymousId);
        if (events.isEmpty()) return;

        List<EventSessionLink> links = new ArrayList<>(events.size());
        int sessionStart = 0;
        for (int index = 1; index <= events.size(); index++) {
            boolean sessionEnded = index == events.size()
                    || Duration.between(events.get(index - 1).eventAt(), events.get(index).eventAt())
                    .compareTo(settings.sessionGap()) > 0;
            if (!sessionEnded) continue;

            List<SessionEventData> group = events.subList(sessionStart, index);
            SessionEventData first = group.getFirst();
            SessionEventData last = group.getLast();
            long sessionId = repository.insertSession(new SessionData(anonymousId, personId, first.eventAt(),
                    last.eventAt(), group.size(), first.pageUrl(), first.utmSource(), first.utmMedium(),
                    first.utmCampaign(), first.clickIdType(), first.referrerHost()));
            for (SessionEventData event : group) links.add(new EventSessionLink(sessionId, event.eventId()));
            sessionStart = index;
        }
        repository.linkEventsToSessions(links);
    }

    private void recomputeAttributionForOrder(String orderId,
                                              AttributionRepository.PipelineSettings settings) {
        OrderData order = repository.findOrder(orderId);
        if (order == null) return;

        Long personId = order.emailHash() == null ? null : repository.findPerson("email", order.emailHash());
        LinkMethod linkMethod = personId == null ? LinkMethod.NONE : LinkMethod.EMAIL_HASH;
        if (personId == null) {
            personId = repository.findPersonForOrderEvent(orderId);
            if (personId != null) linkMethod = LinkMethod.PURCHASE_EVENT;
        }

        List<TouchData> touches = personId == null ? List.of()
                : repository.findTouches(personId, order.createdAt(),
                order.createdAt().minus(settings.attributionWindow()));
        TouchData firstTouch = touches.isEmpty() ? null : touches.getFirst();
        TouchData lastTouch = touches.isEmpty() ? null : touches.getLast();
        boolean counted = FinancialStatus.fromValue(order.financialStatus()).isPaid() && !order.test();
        var fxRate = repository.findFxRate(order.currency());
        var revenueInr = counted
                ? order.total().multiply(fxRate).setScale(AttributionConstants.CURRENCY_SCALE, RoundingMode.HALF_UP)
                : java.math.BigDecimal.ZERO.setScale(AttributionConstants.CURRENCY_SCALE, RoundingMode.HALF_UP);
        saveAttribution(order, personId, linkMethod, AttributionModel.LAST_CLICK, lastTouch, counted,
                revenueInr, fxRate, settings);
        saveAttribution(order, personId, linkMethod, AttributionModel.FIRST_CLICK, firstTouch, counted,
                revenueInr, fxRate, settings);
    }

    private void saveAttribution(OrderData order, Long personId, LinkMethod linkMethod, AttributionModel model,
                                 TouchData touch, boolean counted, java.math.BigDecimal revenueInr,
                                 java.math.BigDecimal fxRate,
                                 AttributionRepository.PipelineSettings settings) {
        boolean linked = personId != null;
        String channel = touch != null ? touch.channel() : linked ? "direct" : "unattributed";
        AttributionReason reason = touch != null ? AttributionReason.CLICK_IN_WINDOW
                : linked ? AttributionReason.NO_MARKETING_TOUCH : AttributionReason.NO_PERSON_LINK;
        int windowDays = touch == null ? Math.toIntExact(settings.attributionWindow().toDays())
                : Math.toIntExact(Duration.between(touch.startedAt(), order.createdAt()).toDays());
        repository.saveAttribution(new AttributionData(order.orderId(), model, personId, linkMethod,
                touch == null ? null : touch.sessionId(), channel, touch == null ? null : touch.platform(),
                touch == null ? null : touch.campaign(), touch == null ? null : touch.startedAt(),
                order.createdAt(), windowDays, reason, counted, revenueInr, fxRate));
    }
}
