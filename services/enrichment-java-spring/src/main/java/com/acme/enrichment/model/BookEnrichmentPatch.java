package com.acme.enrichment.model;

import java.math.BigDecimal;
import java.time.Instant;

public record BookEnrichmentPatch(
        String isbn13,
        String purchaseSource,
        String purchaseUrl,
        BigDecimal confidence,
        Instant resolvedAt,
        boolean force,

        // NEW: will be sent to bookservice
        Integer firstPublishYear
) {}