package com.attributioniq.attribution_iq.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyMetricResponse(LocalDate day, String channel, long orders, BigDecimal revenueInr,
                                  BigDecimal spendInr, BigDecimal roas) { }
