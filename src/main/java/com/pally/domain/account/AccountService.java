package com.pally.domain.account;

import com.pally.domain.subscription.Entitlements;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.UpgradeRequiredException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain service for account management: FCM token registration, family
 * pairing (link-code + claim), account-type upgrade, and family reads.
 *
 * <p>The controller is the only caller; all logic, guards, and repo access
 * live here so the controller stays a thin HTTP delegator.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Avoids confusables (0/O, 1/I) so a tired parent can type it once. */
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 6;
    private static final Duration CODE_TTL = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final PremiumService premiumService;
    private final ClaimRateLimiter claimRateLimiter;

    // ── FCM token ──────────────────────────────────────────────────────────

    @Transactional
    public void setFcmToken(String userId, String token) {
        userRepository.setFcmToken(userId, token);
        log.info("[Account] FCM token updated for user={}", userId);
    }

    // ── Link-code ──────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> issueLinkCode(String userId) {
        User me = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (me.getAccountType() == AccountType.PARENT) {
            throw new BusinessException(
                    "Parents can't generate a link code — ask the child to.", 409);
        }
        if (me.getLinkCode() != null && me.getParentId() != null) {
            throw new BusinessException(
                    "This account is already linked to a parent.", 409);
        }
        if (me.getParentId() != null) {
            throw new BusinessException(
                    "This account is already linked to a parent.", 409);
        }
        String code = generateUniqueCode();
        Instant expires = Instant.now().plus(CODE_TTL);
        userRepository.setLinkCode(userId, code, expires);
        // Mark as CHILD eagerly so subsequent business logic (notifications,
        // shared-note moderation) can treat the account as kid-owned even
        // before the parent claims the code.
        if (me.getAccountType() != AccountType.CHILD) {
            me.setAccountType(AccountType.CHILD);
            userRepository.save(me);
        }
        log.info("[Account] child={} issued link code (ttl={}h)",
                userId, CODE_TTL.toHours());
        return Map.of(
                "code", code,
                "expiresAt", expires.toString(),
                "ttlSeconds", CODE_TTL.getSeconds());
    }

    // ── Claim ──────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> claim(String userId, String rawCode) {
        ClaimRateLimiter.Result rl = claimRateLimiter.tryAcquire(userId);
        if (!rl.allowed()) {
            throw new BusinessException(
                    "Too many incorrect code attempts. Try again in "
                            + rl.retryAfterSeconds() + "s.", 429);
        }

        if (rawCode == null || rawCode.isBlank()) {
            throw new BusinessException("code is required", 400);
        }
        String code = rawCode.trim().toUpperCase();

        User parent = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (parent.getAccountType() == AccountType.CHILD) {
            throw new BusinessException("Child accounts can't claim other accounts.", 403);
        }

        User child = userRepository.findByLinkCode(code)
                .orElseThrow(() -> new BusinessException(
                        "That code is invalid or already used.", 404));
        if (child.getId().equals(parent.getId())) {
            throw new BusinessException("You can't link an account to itself.", 400);
        }
        if (child.getParentId() != null) {
            throw new BusinessException("That child is already linked to a parent.", 409);
        }

        Instant expiresAt = child.getLinkCodeExpiresAt();
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
            // One-shot cleanup so the child can mint a new code immediately.
            userRepository.clearLinkCode(child.getId());
            throw new BusinessException(
                    "That code has expired — ask the child to generate a new one.", 410);
        }

        // Enforce the payer's per-tier child cap.
        SubscriptionTier parentTier = premiumService.resolveTier(parent.getId());
        int maxStudents = Entitlements.forTier(parentTier).maxStudents();
        if (userRepository.countByParentId(parent.getId()) >= maxStudents) {
            throw new UpgradeRequiredException("ADD_STUDENT");
        }

        // Link child to parent.
        child.setParentId(parent.getId());
        child.setAccountType(AccountType.CHILD);
        child.setLinkCode(null);
        child.setLinkCodeExpiresAt(null);
        userRepository.save(child);

        // Promote parent.
        parent.setAccountType(AccountType.PARENT);
        userRepository.save(parent);

        // CHILD's inherited entitlement flips on this link — flush cached value.
        try {
            premiumService.evictEntitlement(child.getId());
        } catch (Exception ignored) {
            // Eviction failure is non-fatal; the TTL bounds the staleness.
        }

        // Success: clear the throttle so a parent who fat-fingered a code
        // earlier isn't penalised on this or their next legitimate claim.
        claimRateLimiter.reset(userId);
        log.info("[Account] parent={} claimed child={}", parent.getId(), child.getId());

        return Map.of(
                "childId", child.getId(),
                "childName", nullToBlank(child.getChildName()),
                "linkedAt", Instant.now().toString());
    }

    // ── Upgrade to parent ──────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> upgradeToParent(String userId) {
        User me = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (me.getAccountType() == AccountType.CHILD) {
            throw new BusinessException(
                    "Child accounts can't be upgraded to PARENT directly.", 403);
        }
        me.setAccountType(AccountType.PARENT);
        userRepository.save(me);
        return Map.of("accountType", me.getAccountType());
    }

    // ── Family ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getFamily(String userId) {
        User me = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        Map<String, Object> body = new HashMap<>();
        body.put("userId", me.getId());
        body.put("accountType", me.getAccountType());

        if (me.getAccountType() == AccountType.CHILD) {
            String parentId = me.getParentId();
            Map<String, Object> parent = parentId == null
                    ? Map.of()
                    : userRepository.findById(parentId)
                            .map(p -> Map.<String, Object>of(
                                    "id", p.getId(),
                                    "displayName", nullToBlank(p.getDisplayName())))
                            .orElse(Map.of());
            body.put("parent", parent);
            body.put("linkCode", nullToBlank(me.getLinkCode()));
            body.put("linkCodeExpiresAt",
                    me.getLinkCodeExpiresAt() == null
                            ? null
                            : me.getLinkCodeExpiresAt().toString());
            body.put("children", List.of());
        } else {
            List<User> kids = userRepository.findByParentId(me.getId());
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
        return body;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String generateUniqueCode() {
        // 32^6 = ~1B combinations vs a handful of in-flight codes — collisions
        // are vanishingly rare, but loop a few times before giving up cleanly.
        for (int attempt = 0; attempt < 5; attempt++) {
            char[] buf = new char[CODE_LENGTH];
            for (int i = 0; i < CODE_LENGTH; i++) {
                buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
            }
            String candidate = new String(buf);
            if (userRepository.findByLinkCode(candidate).isEmpty()) {
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
