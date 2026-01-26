package com.rxlog.gateway;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.web.server.ServerHttpSecurity;

import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    // If you decide to use client roles instead of realm roles, set this to your client id.
    private static final String CLIENT_ID = "rxlog-admin";

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/mobile/sync").permitAll()
                        .pathMatchers("/api/register/**", "/api/mobile/**", "/api/barcodes/**").hasRole("ADMIN")
                        .anyExchange().permitAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter())))
                .build();
    }

    @Bean
    Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthConverter() {        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();

        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        conv.setJwtGrantedAuthoritiesConverter(jwt -> {
            Set<GrantedAuthority> out = new HashSet<>(Optional.ofNullable(scopes.convert(jwt)).orElse(List.of()));
            out.addAll(extractKeycloakRoles(jwt, CLIENT_ID));
            return out;
        });

        return new ReactiveJwtAuthenticationConverterAdapter(conv);
    }

    private static Collection<? extends GrantedAuthority> extractKeycloakRoles(Jwt jwt, String clientId) {
        Set<String> roles = new HashSet<>();

        // realm_access.roles
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null) {
            Object rs = realmAccess.get("roles");
            if (rs instanceof Collection<?> col) {
                for (Object r : col) if (r != null) roles.add(r.toString());
            }
        }

        // resource_access.<clientId>.roles
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess != null && clientId != null) {
            Object client = resourceAccess.get(clientId);
            if (client instanceof Map<?, ?> m) {
                Object rs = m.get("roles");
                if (rs instanceof Collection<?> col) {
                    for (Object r : col) if (r != null) roles.add(r.toString());
                  }
            }
        }

        return roles.stream()
                .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
    }
}