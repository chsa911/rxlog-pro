package com.acme.enrichment.model;

import java.math.BigDecimal;
import java.time.Instant;

public record BookEnrichmentPatch(
        String isbn13,
        String purchaseSource,
        String purchaseUrl,
        String fullTitle,
        BigDecimal confidence,
        Instant resolvedAt,
        boolean force,
        Integer firstPublishYear
) {}