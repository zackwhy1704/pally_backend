package com.pally.infrastructure.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtService {

    private static final long EXPIRY_MS = 30L * 24 * 60 * 60 * 1000; // 30 days
    /// Default role for tokens minted before V36 (or for legacy callers
    /// that don't pass a role) — equivalent to "regular user".
    public static final String DEFAULT_ROLE = "USER";

    private final SecretKey key;

    public JwtService(@Value("${jwt.secret:dev-secret-min-32-chars-long-here-!!}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /// Legacy entrypoint — preserved so older callers compile during the
    /// role rollout. Defaults the claim to USER.
    public String generateToken(String userId) {
        return generateToken(userId, DEFAULT_ROLE);
    }

    public String generateToken(String userId, String role) {
        return Jwts.builder()
                .subject(userId)
                .claim("role",
                        role == null || role.isBlank() ? DEFAULT_ROLE : role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRY_MS))
                .signWith(key)
                .compact();
    }

    public String extractUserId(String token) {
        return parse(token).getSubject();
    }

    /// Reads the role claim, defaulting to USER for tokens minted before
    /// the claim existed. Never returns null.
    public String extractRole(String token) {
        Object raw = parse(token).get("role");
        if (raw == null) return DEFAULT_ROLE;
        String s = raw.toString().trim();
        return s.isEmpty() ? DEFAULT_ROLE : s;
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
