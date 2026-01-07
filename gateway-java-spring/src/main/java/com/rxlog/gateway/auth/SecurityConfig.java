package com.rxlog.gateway.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Protect everything at the gateway except:
     *  - public search endpoints
     *  - login
     *
     * Everything else requires ROLE_ADMIN.
     */
    @Bean
    SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/register/books", "/api/register/search").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyExchange().hasRole("ADMIN")
                )
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter()))
                )
                .build();
    }

    /**
     * Decoder for validating Bearer tokens.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(AuthProperties props) {
        SecretKey key = hmacKey(props.jwtSecret());
        return NimbusReactiveJwtDecoder
                .withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Encoder for issuing JWTs in /api/auth/login.
     */
    @Bean
    JwtEncoder jwtEncoder(AuthProperties props) {
        SecretKey key = hmacKey(props.jwtSecret());
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static ReactiveJwtAuthenticationConverterAdapter jwtAuthConverter() {
        JwtGrantedAuthoritiesConverter gac = new JwtGrantedAuthoritiesConverter();
        gac.setAuthoritiesClaimName("roles");
        gac.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter jac = new JwtAuthenticationConverter();
        jac.setJwtGrantedAuthoritiesConverter(gac);

        return new ReactiveJwtAuthenticationConverterAdapter(jac);
    }

    private static SecretKey hmacKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("auth.jwtSecret must be set");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        // HS256 requires >= 256-bit key material (32 bytes)
        if (bytes.length < 32) {
            throw new IllegalArgumentException("auth.jwtSecret must be at least 32 characters (UTF-8 bytes)");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
