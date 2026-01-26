package com.rxlog.register.config;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String CLIENT_ID = "rxlog-admin";

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        // ✅ PUBLIC is open (read-only)
                        .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()

                        // ✅ ADMIN endpoints secured
                .requestMatchers(HttpMethod.POST, "/api/mobile/sync").permitAll()
                        .requestMatchers("/api/register/**", "/api/mobile/**").hasRole("ADMIN")

                        // everything else requires a valid token
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                );

        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(jwt -> {
            Set<GrantedAuthority> out = new HashSet<>(Optional.ofNullable(scopes.convert(jwt)).orElse(List.of()));
            out.addAll(extractKeycloakRoles(jwt, CLIENT_ID));
            return out;
        });
        return conv;
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