package com.acme.enrichment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "purchase")
public record PurchaseLinkProperties(
        String provider,
        String eurobuchBaseUrl
) {}