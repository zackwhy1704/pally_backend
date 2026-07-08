package com.pally.infrastructure.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Verifies Google and Apple social-login ID tokens against their published JWKS endpoints.
 *
 * <p>Previously the app decoded JWT payload without verifying the signature — any attacker
 * could craft a forged JWT with any email and obtain a valid account token. This class
 * fixes that by:
 * <ol>
 *   <li>Fetching the provider's public keys from their JWKS endpoint</li>
 *   <li>Matching on the {@code kid} header claim</li>
 *   <li>Verifying the RS256 signature</li>
 *   <li>Checking {@code iss}, {@code aud}, and {@code exp} claims</li>
 * </ol>
 *
 * <p>JWKS are cached in-process and refreshed every 12 hours (keys rotate rarely).
 * On cache miss the keys are re-fetched once before failing.
 */
@Component
@Slf4j
public class SocialTokenVerifier {

    private static final String GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final String APPLE_JWKS_URL  = "https://appleid.apple.com/auth/keys";
    private static final String GOOGLE_ISS_1    = "https://accounts.google.com";
    private static final String GOOGLE_ISS_2    = "accounts.google.com";
    private static final String APPLE_ISS       = "https://appleid.apple.com";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // kid → PublicKey caches; refreshed every 12h
    private final ConcurrentHashMap<String, PublicKey> googleKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PublicKey> appleKeys  = new ConcurrentHashMap<>();

    private final ScheduledExecutorService keyRefresher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jwks-refresher");
                t.setDaemon(true);
                return t;
            });

    public SocialTokenVerifier(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient   = webClientBuilder.build();
        this.objectMapper = objectMapper;
        refreshKeys();
        keyRefresher.scheduleAtFixedRate(this::refreshKeys, 12, 12, TimeUnit.HOURS);
    }

    /**
     * @param emailVerified whether the PROVIDER asserts this email is verified. Only a
     *   verified email may be matched/linked to an existing account — an unverified
     *   Google email is attacker-controllable, so it must never auto-link (takeover).
     * @param provider "google" or "apple" — the account key namespace for sub-keying.
     */
    public record VerifiedClaims(String email, boolean emailVerified, String name,
                                 String subject, String provider) {}

    /**
     * Verifies a Google idToken. Returns claims on success or throws on failure.
     * Callers must supply their Google OAuth client ID(s) as {@code allowedAudiences}.
     */
    public VerifiedClaims verifyGoogle(String idToken, List<String> allowedAudiences) {
        Map<String, Object> payload = verifyToken(idToken, googleKeys, GOOGLE_JWKS_URL, allowedAudiences);
        String iss = (String) payload.get("iss");
        if (!GOOGLE_ISS_1.equals(iss) && !GOOGLE_ISS_2.equals(iss)) {
            throw new SecurityException("Google token issuer invalid: " + iss);
        }
        return new VerifiedClaims(
                (String) payload.get("email"),
                parseEmailVerified(payload.get("email_verified")),
                (String) payload.get("name"),
                (String) payload.get("sub"),
                "google");
    }

    /** Google/Apple may send email_verified as a boolean or the string "true". */
    private static boolean parseEmailVerified(Object claim) {
        if (claim instanceof Boolean b) return b;
        if (claim instanceof String s) return "true".equalsIgnoreCase(s);
        return false; // absent → NOT verified (fail-closed for email matching)
    }

    /**
     * Verifies an Apple identityToken. Returns claims on success or throws on failure.
     * Apple tokens may not contain an email on repeated logins — callers should handle null.
     */
    public VerifiedClaims verifyApple(String identityToken, List<String> allowedAudiences) {
        Map<String, Object> payload = verifyToken(identityToken, appleKeys, APPLE_JWKS_URL, allowedAudiences);
        String iss = (String) payload.get("iss");
        if (!APPLE_ISS.equals(iss)) {
            throw new SecurityException("Apple token issuer invalid: " + iss);
        }
        // Apple only mints an email claim for emails IT verified (real or private-relay),
        // so an email present in an Apple identity token is verified by construction.
        return new VerifiedClaims(
                (String) payload.get("email"),
                parseEmailVerified(payload.getOrDefault("email_verified", true)),
                null, // Apple doesn't return name after first login
                (String) payload.get("sub"),
                "apple");
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> verifyToken(
            String jwt,
            ConcurrentHashMap<String, PublicKey> keyCache,
            String jwksUrl,
            List<String> allowedAudiences) {

        String[] parts = jwt.split("\\.");
        if (parts.length != 3) throw new SecurityException("Invalid JWT format");

        Map<String, Object> header  = decodeSegment(parts[0]);
        Map<String, Object> payload = decodeSegment(parts[1]);

        // ── Expiry check ─────────────────────────────────────────────────────
        Number exp = (Number) payload.get("exp");
        if (exp == null || exp.longValue() < System.currentTimeMillis() / 1000L) {
            throw new SecurityException("Social token expired");
        }

        // ── Audience check ───────────────────────────────────────────────────
        Object aud = payload.get("aud");
        String audStr = aud instanceof List ? ((List<String>) aud).get(0) : String.valueOf(aud);
        if (allowedAudiences != null && !allowedAudiences.isEmpty()
                && !allowedAudiences.contains(audStr)) {
            log.warn("[SocialAuth] Audience mismatch — received '{}', allowed {}", audStr, allowedAudiences);
            throw new SecurityException("Social token audience invalid");
        }

        // ── Signature verification ────────────────────────────────────────────
        String kid = (String) header.get("kid");
        PublicKey key = kid != null ? keyCache.get(kid) : null;
        if (key == null) {
            // Refresh once and retry (key rotation)
            log.info("[SocialAuth] kid={} not in cache — refreshing JWKS from {}", kid, jwksUrl);
            loadKeys(jwksUrl, keyCache);
            key = kid != null ? keyCache.get(kid) : null;
        }
        if (key == null) {
            throw new SecurityException("Unknown signing key kid=" + kid);
        }

        boolean valid = verifyRs256(
                (parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Base64.getUrlDecoder().decode(padBase64(parts[2])),
                key);
        if (!valid) {
            throw new SecurityException("Social token signature invalid");
        }

        return payload;
    }

    private boolean verifyRs256(byte[] data, byte[] signatureBytes, PublicKey key) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update(data);
            return sig.verify(signatureBytes);
        } catch (Exception e) {
            log.error("[SocialAuth] Signature verification error: {}", e.getMessage());
            return false;
        }
    }

    private void refreshKeys() {
        loadKeys(GOOGLE_JWKS_URL, googleKeys);
        loadKeys(APPLE_JWKS_URL, appleKeys);
    }

    @SuppressWarnings("unchecked")
    private void loadKeys(String url, ConcurrentHashMap<String, PublicKey> cache) {
        try {
            String json = webClient.get().uri(url)
                    .retrieve().bodyToMono(String.class).block(java.time.Duration.ofSeconds(10));
            Map<String, Object> jwks = objectMapper.readValue(json, Map.class);
            List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
            if (keys == null) return;

            ConcurrentHashMap<String, PublicKey> fresh = new ConcurrentHashMap<>();
            for (Map<String, Object> jwk : keys) {
                String alg = (String) jwk.get("alg");
                if (alg != null && !alg.startsWith("RS")) continue;
                String kid = (String) jwk.get("kid");
                String n   = (String) jwk.get("n");
                String e   = (String) jwk.get("e");
                if (kid == null || n == null || e == null) continue;
                try {
                    BigInteger modulus  = new BigInteger(1, Base64.getUrlDecoder().decode(padBase64(n)));
                    BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(padBase64(e)));
                    PublicKey pk = KeyFactory.getInstance("RSA")
                            .generatePublic(new RSAPublicKeySpec(modulus, exponent));
                    fresh.put(kid, pk);
                } catch (Exception ex) {
                    log.warn("[SocialAuth] Failed to parse key kid={}: {}", kid, ex.getMessage());
                }
            }
            cache.clear();
            cache.putAll(fresh);
            log.info("[SocialAuth] Loaded {} keys from {}", fresh.size(), url);
        } catch (Exception e) {
            log.error("[SocialAuth] Failed to load JWKS from {}: {}", url, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeSegment(String segment) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(segment));
            return objectMapper.readValue(decoded, Map.class);
        } catch (Exception e) {
            throw new SecurityException("Failed to decode JWT segment: " + e.getMessage());
        }
    }

    private String padBase64(String base64Url) {
        int padding = (4 - base64Url.length() % 4) % 4;
        return base64Url + "=".repeat(padding);
    }
}
