package com.attributioniq.attribution_iq.api.dto;

import com.attributioniq.attribution_iq.domain.AttributionModel;

import java.time.LocalDate;
import java.util.List;

public record ChannelReportResponse(LocalDate from, LocalDate to, AttributionModel model,
                                    List<ChannelSummaryResponse> channels,
                                    List<DailyMetricResponse> daily) { }
