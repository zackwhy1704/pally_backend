package com.pally.api.account;

import com.pally.domain.account.AccountType;

import com.pally.domain.account.usecase.DeleteAccountUseCase;
import com.pally.infrastructure.auth.JwtService;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.domain.subscription.Entitlements;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.UpgradeRequiredException;
import com.pally.shared.response.ApiResponse;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parent ⇄ child account linking (the audit's Gap A).
 *
 * <p>Three account types live on the {@code users} table:
 *  - {@code SOLO}   — legacy default; can become PARENT by claiming a child.
 *  - {@code PARENT} — owns one or more CHILD accounts via {@code parent_id}.
 *  - {@code CHILD}  — owned by exactly one parent; can generate a fresh
 *                     link code while unclaimed.
 *
 * <p>Pairing flow (no email needed for the child):
 *   1. Child app calls {@code POST /account/link-code} → 6-char code, 24h TTL.
 *   2. Parent app calls {@code POST /account/claim} with that code → the
 *      caller is promoted from SOLO→PARENT (if needed), child.parent_id is
 *      set, and the code is cleared so it can't be re-used.
 *
 * <p>{@code GET /account/family} returns whichever side the caller is on,
 * so the same endpoint powers both the child's "Connected to a parent?"
 * indicator and the parent dashboard's child switcher.
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private static final SecureRandom RANDOM = new SecureRandom();
    /// Avoids confusables (0/O, 1/I) so a tired parent can type it once.
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 6;
    private static final Duration CODE_TTL = Duration.ofMinutes(15);

    /// Claim throttle: a parent who repeatedly types a wrong/expired code is
    /// guessing. Cap FAILED claims per authenticated parent so the 32^6 code
    /// space can't be brute-forced. Successful claims reset the counter.
    private static final int CLAIM_FAIL_LIMIT = 5;
    private static final long CLAIM_WINDOW_MS = Duration.ofHours(1).toMillis();

    private final UserJpaRepository userRepo;
    private final com.pally.domain.subscription.PremiumService premiumService;
    private final DeleteAccountUseCase deleteAccountUseCase;
    private final JwtService jwtService;
    private final com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter rateLimiter;

    /**
     * Saves or updates the user's FCM push notification token.
     */
    @PostMapping("/fcm-token")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> setFcmToken(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String token = body == null ? null : body.get("token");
        if (token == null || token.isBlank()) {
            throw new BusinessException("token is required", 400);
        }
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        user.setFcmToken(token);
        userRepo.save(user);
        log.info("[Account] FCM token updated for user={}", userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/link-code")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> issueLinkCode(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity me = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (me.getAccountType() == AccountType.PARENT) {
            throw new BusinessException(
                    "Parents can't generate a link code — ask the child to.",
                    409);
        }
        if (me.getParentId() != null) {
            throw new BusinessException(
                    "This account is already linked to a parent.", 409);
        }
        // Mark as CHILD eagerly so subsequent business logic (notifications,
        // shared-note moderation) can treat the account as kid-owned even
        // before the parent claims the code.
        if (me.getAccountType() != AccountType.CHILD) {
            me.setAccountType(AccountType.CHILD);
        }
        String code = generateUniqueCode();
        Instant expires = Instant.now().plus(CODE_TTL);
        me.setLinkCode(code);
        me.setLinkCodeExpiresAt(expires);
        userRepo.save(me);
        log.info("[Account] child={} issued link code (ttl={}h)",
                userId, CODE_TTL.toHours());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "code", code,
                "expiresAt", expires.toString(),
                "ttlSeconds", CODE_TTL.getSeconds())));
    }

    @PostMapping("/claim")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> claim(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        // Throttle FAILED claims per authenticated parent so a wrong/expired
        // code can't be brute-forced. The hit is recorded here; a fully
        // successful claim resets the key below (mirrors AuthController.login),
        // so only failures accumulate toward the limit.
        String rateKey = "claim:" + userId;
        var rl = rateLimiter.tryAcquire(rateKey, CLAIM_FAIL_LIMIT, CLAIM_WINDOW_MS);
        if (!rl.allowed()) {
            throw new BusinessException(
                    "Too many incorrect code attempts. Try again in "
                            + rl.retryAfterSeconds() + "s.", 429);
        }

        String raw = body == null ? null : body.get("code");
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("code is required", 400);
        }
        String code = raw.trim().toUpperCase();
        UserJpaEntity parent = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (parent.getAccountType() == AccountType.CHILD) {
            throw new BusinessException(
                    "Child accounts can't claim other accounts.", 403);
        }
        UserJpaEntity child = userRepo.findByLinkCode(code)
                .orElseThrow(() -> new BusinessException(
                        "That code is invalid or already used.", 404));
        if (child.getId().equals(parent.getId())) {
            throw new BusinessException(
                    "You can't link an account to itself.", 400);
        }
        if (child.getParentId() != null) {
            throw new BusinessException(
                    "That child is already linked to a parent.", 409);
        }
        Instant expiresAt = child.getLinkCodeExpiresAt();
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
            // One-shot cleanup so the child can mint a new code immediately.
            child.setLinkCode(null);
            child.setLinkCodeExpiresAt(null);
            userRepo.save(child);
            throw new BusinessException(
                    "That code has expired — ask the child to generate a new one.",
                    410);
        }
        // Enforce the payer's per-tier child cap on the canonical claim path.
        // (Previously only enforced on the now-removed FamilyController.joinFamily,
        // so a FREE parent could link unlimited children.) Reads the cap from the
        // entitlement table; rejected atomically BEFORE any write to the child.
        SubscriptionTier parentTier = premiumService.resolveTier(parent.getId());
        int maxStudents = Entitlements.forTier(parentTier).maxStudents();
        if (userRepo.countByParentId(parent.getId()) >= maxStudents) {
            throw new UpgradeRequiredException("ADD_STUDENT");
        }
        child.setParentId(parent.getId());
        child.setAccountType(AccountType.CHILD);
        child.setLinkCode(null);
        child.setLinkCodeExpiresAt(null);
        userRepo.save(child);

        parent.setAccountType(AccountType.PARENT);
        userRepo.save(parent);
        // CHILD's inherited entitlement flips on this link — flush their
        // cached resolve() value so the next gate read sees the parent's
        // premium status without waiting for the TTL.
        try {
            premiumService.evictEntitlement(child.getId());
        } catch (Exception ignored) {
            // Eviction failure is non-fatal; the TTL bounds the staleness.
        }
        // Success: clear the throttle so a parent who fat-fingered a code
        // earlier isn't penalised on this or their next legitimate claim.
        rateLimiter.reset(rateKey);
        log.info("[Account] parent={} claimed child={}", parent.getId(), child.getId());

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "childId", child.getId(),
                "childName", nullToBlank(child.getChildName()),
                "linkedAt", Instant.now().toString())));
    }

    @PostMapping("/upgrade-to-parent")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> upgradeToParent(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity me = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (me.getAccountType() == AccountType.CHILD) {
            throw new BusinessException(
                    "Child accounts can't be upgraded to PARENT directly.", 403);
        }
        me.setAccountType(AccountType.PARENT);
        userRepo.save(me);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "accountType", me.getAccountType())));
    }

    @GetMapping("/family")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> family(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity me = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        Map<String, Object> body = new HashMap<>();
        body.put("userId", me.getId());
        body.put("accountType", me.getAccountType());

        if (me.getAccountType() == AccountType.CHILD) {
            String parentId = me.getParentId();
            Map<String, Object> parent = parentId == null
                    ? Map.of()
                    : userRepo.findById(parentId)
                            .map(p -> Map.<String, Object>of(
                                    "id", p.getId(),
                                    "displayName",
                                            nullToBlank(p.getDisplayName())))
                            .orElse(Map.of());
            body.put("parent", parent);
            body.put("linkCode", nullToBlank(me.getLinkCode()));
            body.put("linkCodeExpiresAt",
                    me.getLinkCodeExpiresAt() == null
                            ? null
                            : me.getLinkCodeExpiresAt().toString());
            body.put("children", List.of());
        } else {
            List<UserJpaEntity> kids = userRepo.findByParentId(me.getId());
            List<Map<String, Object>> kidDtos = kids.stream()
                    .map(k -> Map.<String, Object>of(
                            "id", k.getId(),
                            "displayName", nullToBlank(k.getDisplayName()),
                            "childName", nullToBlank(k.getChildName()),
                            "level", k.getLevel(),
                            "xp", k.getXp(),
                            "streakDays", k.getStreakDays()))
                    .toList();
            body.put("children", kidDtos);
            body.put("parent", Map.of());
        }
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    /**
     * Permanently deletes the authenticated user's account and all their data.
     *
     * <p>Required by Apple App Store guideline 5.1.1 and PDPA.
     * Only the authenticated user can delete their own account.
     * Returns 409 Conflict if the user is a PARENT with linked children.
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteAccount(
            @AuthenticationPrincipal String userId,
            HttpServletRequest request) {

        // Extract jti and expiry from the current token so we can revoke it.
        String jti = null;
        java.time.Instant tokenExpiry = null;
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                jti = jwtService.extractJti(token);
                tokenExpiry = jwtService.extractExpiration(token);
            } catch (JwtException e) {
                log.warn("[DeleteAccount] Could not extract jti from token: {}", e.getMessage());
            }
        }

        deleteAccountUseCase.execute(userId, jti, tokenExpiry);
        return ResponseEntity.noContent().build();
    }

    private String generateUniqueCode() {
        // 32^6 = ~1B combinations vs a handful of in-flight codes — collisions
        // are vanishingly rare, but loop a few times before giving up cleanly.
        for (int attempt = 0; attempt < 5; attempt++) {
            char[] buf = new char[CODE_LENGTH];
            for (int i = 0; i < CODE_LENGTH; i++) {
                buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
            }
            String candidate = new String(buf);
            if (userRepo.findByLinkCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new BusinessException(
                "Could not allocate a unique link code — try again", 503);
    }

    private static String nullToBlank(String s) {
        return s == null ? "" : s;
    }

}
