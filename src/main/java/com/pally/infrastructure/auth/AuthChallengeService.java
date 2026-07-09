package com.pally.infrastructure.auth;

import com.pally.infrastructure.persistence.auth.AuthChallengeJpaEntity;
import com.pally.infrastructure.persistence.auth.AuthChallengeJpaRepository;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The ONE single-use challenge store, two consumers:
 * <ul>
 *   <li>LINK_SOCIAL — a 6-digit in-app code (10-min TTL) to link a social provider to a
 *       passwordless account (no deep links — the pally scheme is fragile).</li>
 *   <li>PASSWORD_RESET — a web-link token (≤1h TTL).</li>
 * </ul>
 * Codes/tokens are stored only as SHA-256 hashes, single-use, with a per-challenge
 * attempt cap so a 6-digit code can't be brute-forced.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthChallengeService {

    public static final String PURPOSE_LINK = "LINK_SOCIAL";
    public static final String PURPOSE_RESET = "PASSWORD_RESET";
    /// ACCOUNT DELETION Phase 1: 6-digit re-auth code for a PASSWORDLESS (social)
    /// account to CONFIRM a deletion request (a bearer token alone can never initiate
    /// deletion). No schema change — a new value of the existing purpose column.
    public static final String PURPOSE_DELETE = "DELETE_ACCOUNT";
    /// ACCOUNT DELETION Phase 1: single-use restore-link token, valid for the whole
    /// grace window, emailed to the requester (and a child's parent) so either can
    /// cancel the deletion from the link.
    public static final String PURPOSE_RESTORE = "RESTORE_ACCOUNT";
    private static final String PENDING = "PENDING", CONSUMED = "CONSUMED", EXPIRED = "EXPIRED";
    private static final long LINK_TTL_MIN = 10;
    private static final long RESET_TTL_MIN = 60;
    private static final long DELETE_CODE_TTL_MIN = 10;
    private static final int MAX_ATTEMPTS = 5;

    private final AuthChallengeJpaRepository repo;
    private static final SecureRandom RNG = new SecureRandom();

    public record ConsumedLink(String userId, String provider, String providerSub) {}

    /** Creates a LINK challenge and returns the PLAINTEXT 6-digit code (to email). */
    @Transactional
    public String createLinkCode(String userId, String provider, String providerSub) {
        invalidatePending(userId, PURPOSE_LINK);
        String code = String.format("%06d", RNG.nextInt(1_000_000));
        AuthChallengeJpaEntity c = base(userId, PURPOSE_LINK, code, LINK_TTL_MIN);
        c.setProvider(provider);
        c.setProviderSub(providerSub);
        repo.save(c);
        return code;
    }

    /** Creates a RESET challenge and returns the PLAINTEXT token (for the web link). */
    @Transactional
    public String createResetToken(String userId) {
        invalidatePending(userId, PURPOSE_RESET);
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        repo.save(base(userId, PURPOSE_RESET, token, RESET_TTL_MIN));
        return token;
    }

    /**
     * Verifies a LINK code for a user (constant-time on the hash), enforcing expiry +
     * attempt cap. On success consumes it and returns the (provider, sub) to stamp.
     */
    @Transactional
    public Optional<ConsumedLink> consumeLinkCode(String userId, String provider, String code) {
        var pending = repo.findByUserIdAndPurposeAndStatus(userId, PURPOSE_LINK, PENDING);
        for (AuthChallengeJpaEntity c : pending) {
            if (Instant.now().isAfter(c.getExpiresAt())) { c.setStatus(EXPIRED); repo.save(c); continue; }
            c.setAttempts(c.getAttempts() + 1);
            if (c.getAttempts() > MAX_ATTEMPTS) { c.setStatus(EXPIRED); repo.save(c); continue; }
            boolean match = MessageDigest.isEqual(
                    c.getCodeHash().getBytes(StandardCharsets.UTF_8),
                    sha256Hex(code).getBytes(StandardCharsets.UTF_8))
                    && provider != null && provider.equals(c.getProvider());
            if (match) {
                c.setStatus(CONSUMED);
                repo.save(c);
                return Optional.of(new ConsumedLink(c.getUserId(), c.getProvider(), c.getProviderSub()));
            }
            repo.save(c); // wrong code — attempt recorded
        }
        return Optional.empty();
    }

    /** Verifies + consumes a RESET token, returning the owning userId. */
    @Transactional
    public Optional<String> consumeResetToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return repo.findByCodeHashAndPurposeAndStatus(sha256Hex(token), PURPOSE_RESET, PENDING)
                .filter(c -> {
                    if (Instant.now().isAfter(c.getExpiresAt())) { c.setStatus(EXPIRED); repo.save(c); return false; }
                    return true;
                })
                .map(c -> { c.setStatus(CONSUMED); repo.save(c); return c.getUserId(); });
    }

    // ── ACCOUNT DELETION Phase 1 ─────────────────────────────────────────────

    /** Creates a DELETE_ACCOUNT re-auth challenge; returns the PLAINTEXT 6-digit code (to email). */
    @Transactional
    public String createDeleteCode(String userId) {
        invalidatePending(userId, PURPOSE_DELETE);
        String code = String.format("%06d", RNG.nextInt(1_000_000));
        repo.save(base(userId, PURPOSE_DELETE, code, DELETE_CODE_TTL_MIN));
        return code;
    }

    /**
     * Verifies a DELETE_ACCOUNT code for a user (constant-time on the hash), enforcing
     * expiry + attempt cap. Consumes it on success. True only when a valid, unexpired,
     * within-attempt-cap code matches — the passwordless re-auth gate.
     */
    @Transactional
    public boolean consumeDeleteCode(String userId, String code) {
        if (code == null || code.isBlank()) return false;
        for (AuthChallengeJpaEntity c : repo.findByUserIdAndPurposeAndStatus(userId, PURPOSE_DELETE, PENDING)) {
            if (Instant.now().isAfter(c.getExpiresAt())) { c.setStatus(EXPIRED); repo.save(c); continue; }
            c.setAttempts(c.getAttempts() + 1);
            if (c.getAttempts() > MAX_ATTEMPTS) { c.setStatus(EXPIRED); repo.save(c); continue; }
            boolean match = MessageDigest.isEqual(
                    c.getCodeHash().getBytes(StandardCharsets.UTF_8),
                    sha256Hex(code).getBytes(StandardCharsets.UTF_8));
            if (match) { c.setStatus(CONSUMED); repo.save(c); return true; }
            repo.save(c); // wrong code — attempt recorded
        }
        return false;
    }

    /**
     * Creates a single-use RESTORE_ACCOUNT token valid for the whole grace window and
     * returns the PLAINTEXT token (for the emailed restore link). One active token per
     * user — a re-request invalidates the prior one.
     */
    @Transactional
    public String createRestoreToken(String userId, long ttlDays) {
        invalidatePending(userId, PURPOSE_RESTORE);
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        repo.save(base(userId, PURPOSE_RESTORE, token, ttlDays * 24 * 60));
        return token;
    }

    /** Verifies + consumes a RESTORE_ACCOUNT token, returning the owning userId. */
    @Transactional
    public Optional<String> consumeRestoreToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return repo.findByCodeHashAndPurposeAndStatus(sha256Hex(token), PURPOSE_RESTORE, PENDING)
                .filter(c -> {
                    if (Instant.now().isAfter(c.getExpiresAt())) { c.setStatus(EXPIRED); repo.save(c); return false; }
                    return true;
                })
                .map(c -> { c.setStatus(CONSUMED); repo.save(c); return c.getUserId(); });
    }

    private void invalidatePending(String userId, String purpose) {
        for (var c : repo.findByUserIdAndPurposeAndStatus(userId, purpose, PENDING)) {
            c.setStatus(EXPIRED);
            repo.save(c);
        }
    }

    private AuthChallengeJpaEntity base(String userId, String purpose, String secret, long ttlMin) {
        AuthChallengeJpaEntity c = new AuthChallengeJpaEntity();
        c.setId(IdGenerator.newId());
        c.setUserId(userId);
        c.setPurpose(purpose);
        c.setCodeHash(sha256Hex(secret));
        c.setStatus(PENDING);
        c.setCreatedAt(Instant.now());
        c.setExpiresAt(Instant.now().plus(ttlMin, ChronoUnit.MINUTES));
        return c;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
