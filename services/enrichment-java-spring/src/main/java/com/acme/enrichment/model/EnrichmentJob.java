package com.acme.enrichment.model;

public record EnrichmentJob(long jobId, String bookId, int attempts) {}