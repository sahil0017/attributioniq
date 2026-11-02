package com.attributioniq.attribution_iq.service;

import com.attributioniq.attribution_iq.database.ReportRepository;
import com.attributioniq.attribution_iq.database.ReportRepository.DailyMetric;
import com.attributioniq.attribution_iq.api.dto.ChannelReportResponse;
import com.attributioniq.attribution_iq.api.dto.ChannelSummaryResponse;
import com.attributioniq.attribution_iq.api.dto.DailyMetricResponse;
import com.attributioniq.attribution_iq.api.dto.ReportDataRangeResponse;
import com.attributioniq.attribution_iq.config.AttributionConstants;
import com.attributioniq.attribution_iq.domain.AttributionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates report requests and builds API response DTOs from daily SQL results. */
@Service
public class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private final ReportRepository repository;

    public ReportService(ReportRepository repository) {
        this.repository = repository;
    }

    /** Returns daily metrics and their channel-level totals for an inclusive date range. */
    public ChannelReportResponse channels(LocalDate from, LocalDate to, AttributionModel attributionModel) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be on or before to");
        }
        List<DailyMetricResponse> daily = repository.findDailyMetrics(from, to, attributionModel).stream()
                .map(ReportService::toDailyReport)
                .toList();

        Map<String, ChannelTotals> totals = new LinkedHashMap<>();
        for (DailyMetricResponse row : daily) {
            ChannelTotals aggregate = totals.computeIfAbsent(row.channel(), ignored -> new ChannelTotals());
            aggregate.orders += row.orders();
            aggregate.revenue = aggregate.revenue.add(row.revenueInr());
            aggregate.spend = aggregate.spend.add(row.spendInr());
        }
        List<ChannelSummaryResponse> channels = new ArrayList<>();
        totals.forEach((channel, aggregate) -> {
            BigDecimal roas = aggregate.spend.signum() == 0
                    ? null : aggregate.revenue.divide(aggregate.spend, AttributionConstants.CURRENCY_SCALE,
                    RoundingMode.HALF_UP);
            channels.add(new ChannelSummaryResponse(channel, aggregate.orders,
                    aggregate.revenue.setScale(AttributionConstants.CURRENCY_SCALE, RoundingMode.HALF_UP),
                    aggregate.spend.setScale(AttributionConstants.CURRENCY_SCALE, RoundingMode.HALF_UP), roas));
        });
        log.info("Channel report generated from={} to={} model={} channels={}", from, to,
                attributionModel.value(), channels.size());
        return new ChannelReportResponse(from, to, attributionModel, List.copyOf(channels), List.copyOf(daily));
    }

    /** Returns the range containing available attributed revenue or ad-spend data. */
    public ReportDataRangeResponse dataRange() {
        ReportRepository.DataRange range = repository.findDataRange();
        return new ReportDataRangeResponse(range.from(), range.to());
    }

    private static DailyMetricResponse toDailyReport(DailyMetric metric) {
        return new DailyMetricResponse(metric.day(), metric.channel(), metric.orders(), metric.revenueInr(),
                metric.spendInr(), metric.roas());
    }

    private static final class ChannelTotals {
        private long orders;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal spend = BigDecimal.ZERO;
    }
}
