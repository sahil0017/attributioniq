package com.attributioniq.attribution_iq.api.dto;

import java.time.LocalDate;

public record ReportDataRangeResponse(LocalDate from, LocalDate to) { }
