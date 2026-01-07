package com.rxlog.gateway.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthProperties props;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;

    public AuthController(AuthProperties props, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder) {
        this.props = props;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req == null || req.username == null || req.password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing_credentials"));
        }

        String u = req.username.trim();
        String p = req.password;

        if (!u.equals(props.adminUser())) {
            // avoid user enumeration
            return ResponseEntity.status(401).body(Map.of("error", "invalid_credentials"));
        }
        if (!passwordEncoder.matches(p, props.adminPasswordBcrypt())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_credentials"));
        }

        Instant now = Instant.now();
        Instant exp = now.plus(props.effectiveJwtTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("rxlog")
                .issuedAt(now)
                .expiresAt(exp)
                .subject(u)
                .claim("roles", List.of("ADMIN"))
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return ResponseEntity.ok(Map.of(
                "token", token,
                "expiresAt", exp.toString()
        ));
    }
}
