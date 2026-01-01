package com.rxlog.register.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.Instant;

public record BookEnrichmentPatch(
        String isbn13,
        String purchaseSource,
        String purchaseUrl,

        @DecimalMin("0.0") @DecimalMax("1.0")
        BigDecimal confidence,

        Instant resolvedAt,
        boolean force,

        // NEW: work-level first publish year
        Integer firstPublishYear
) {}
