package com.attributioniq.attribution_iq.api.dto;

import java.math.BigDecimal;

public record ChannelSummaryResponse(String channel, long orders, BigDecimal revenueInr,
                                     BigDecimal spendInr, BigDecimal roas) { }
