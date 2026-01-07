package com.rxlog.gateway.auth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Central auth settings for the gateway.
 *
 * Configure via env vars, e.g.:
 *  - AUTH_ADMIN_USER
 *  - AUTH_ADMIN_PASSWORD_BCRYPT
 *  - AUTH_JWT_SECRET
 *  - AUTH_JWT_TTL (e.g. PT12H)
 */
@Validated
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        @NotBlank String adminUser,
        @NotBlank String adminPasswordBcrypt,
        @NotBlank String jwtSecret,
        Duration jwtTtl
) {
    public Duration effectiveJwtTtl() {
        return (jwtTtl == null || jwtTtl.isZero() || jwtTtl.isNegative())
                ? Duration.ofHours(12)
                : jwtTtl;
    }
}
