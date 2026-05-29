package com.pally.api.account;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
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
    private static final Duration CODE_TTL = Duration.ofHours(24);

    private final UserJpaRepository userRepo;

    @PostMapping("/link-code")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> issueLinkCode(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity me = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if ("PARENT".equals(me.getAccountType())) {
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
        if (!"CHILD".equals(me.getAccountType())) {
            me.setAccountType("CHILD");
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
        String raw = body == null ? null : body.get("code");
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("code is required", 400);
        }
        String code = raw.trim().toUpperCase();
        UserJpaEntity parent = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if ("CHILD".equals(parent.getAccountType())) {
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
        child.setParentId(parent.getId());
        child.setAccountType("CHILD");
        child.setLinkCode(null);
        child.setLinkCodeExpiresAt(null);
        userRepo.save(child);

        parent.setAccountType("PARENT");
        userRepo.save(parent);
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
        if ("CHILD".equals(me.getAccountType())) {
            throw new BusinessException(
                    "Child accounts can't be upgraded to PARENT directly.", 403);
        }
        me.setAccountType("PARENT");
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

        if ("CHILD".equals(me.getAccountType())) {
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
