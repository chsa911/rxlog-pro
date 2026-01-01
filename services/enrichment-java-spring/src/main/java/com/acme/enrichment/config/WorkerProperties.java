package com.acme.enrichment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "worker")
public record WorkerProperties(
        boolean enabled,
        long pollDelayMs,
        int batchSize,
        int maxAttempts
) {}