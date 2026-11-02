package com.attributioniq.attribution_iq.api;

import com.attributioniq.attribution_iq.api.dto.ChannelReportResponse;
import com.attributioniq.attribution_iq.api.dto.DailyMetricResponse;
import com.attributioniq.attribution_iq.api.dto.ReportDataRangeResponse;
import com.attributioniq.attribution_iq.domain.AttributionModel;
import com.attributioniq.attribution_iq.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;

/** Exposes date-filtered channel summaries and daily reporting data. */
@RestController
@Tag(name = "Reports", description = "Channel revenue, spend, and attribution reporting")
public class ReportController {
    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    /** Aggregates report rows for an inclusive UTC date range. */
    @GetMapping(ApiPaths.REPORTS)
    @Operation(summary = "Get channel and daily performance",
            description = "Returns inclusive UTC date-range metrics. The attribution model defaults to last_click.")
    public ChannelReportResponse channels(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "last_click") AttributionModel model) {
        return reports.channels(from, to, model);
    }

    /** Returns only the daily rows from the channel report. */
    @GetMapping(ApiPaths.REPORTS + ApiPaths.DAILY_REPORTS)
    @Operation(summary = "Get daily channel performance")
    public java.util.List<DailyMetricResponse> daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "last_click") AttributionModel model) {
        return reports.channels(from, to, model).daily();
    }

    /** Returns the earliest and latest date with attributed revenue or spend. */
    @GetMapping(ApiPaths.REPORT_DATA_RANGE)
    @Operation(summary = "Get the available reporting date range")
    public ReportDataRangeResponse dataRange() {
        return reports.dataRange();
    }
}
