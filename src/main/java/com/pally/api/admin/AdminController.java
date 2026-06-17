package com.pally.api.admin;

import com.pally.domain.centre.CentreInviteService;
import com.pally.infrastructure.persistence.chat.ChatMessageJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ChatMessageJpaRepository chatRepo;
    private final OrganizationJpaRepository orgRepo;
    private final UserJpaRepository userRepo;
    private final CentreInviteService inviteService;

    @GetMapping("/model-usage")
    public Map<String, Object> getModelUsage(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "7") int days
    ) {
        Instant since = Instant.now().minus(Duration.ofDays(days));

        long haikuCount  = chatRepo.countByModelUsedAndCreatedAtAfter("claude-haiku-4-5-20251001", since);
        long sonnetCount = chatRepo.countByModelUsedAndCreatedAtAfter("claude-sonnet-4-6", since);

        // Cache-effectiveness metrics (read from chat_messages columns)
        long cacheHits        = chatRepo.countCacheHitsAfter(since);
        long cacheable        = chatRepo.countCacheMissesAfter(since); // messages with cache_hit tracked
        long cacheReadTokens  = chatRepo.sumCacheReadTokensAfter(since);
        long cacheWriteTokens = chatRepo.sumCacheWriteTokensAfter(since);
        long totalInputTokens = chatRepo.sumTotalInputTokensAfter(since);
        long totalOutputTokens = chatRepo.sumTotalOutputTokensAfter(since);

        // Saving = read tokens billed at 0.1× instead of 1× — so 0.9× of the read price saved
        // Haiku input: $0.80/M tokens = $0.0000008 per token
        double haikuInputPricePerToken = 0.80 / 1_000_000.0;
        double cacheReadSavingUsd = cacheReadTokens * haikuInputPricePerToken * 0.9;
        double hitRate = cacheable > 0 ? (double) cacheHits / cacheable * 100 : 0;

        return Map.of(
                "period_days", days,
                "haiku_calls", haikuCount,
                "sonnet_calls_historical", sonnetCount,
                "total_calls", haikuCount + sonnetCount,
                "estimated_cost_usd", estimateCost(haikuCount, sonnetCount),
                "cache", Map.of(
                        "hit_count", cacheHits,
                        "hit_rate_pct", Math.round(hitRate * 10) / 10.0,
                        "cache_read_tokens", cacheReadTokens,
                        "cache_write_tokens", cacheWriteTokens,
                        "estimated_saving_usd", Math.round(cacheReadSavingUsd * 10000) / 10000.0
                ),
                "tokens", Map.of(
                        "total_input", totalInputTokens,
                        "total_output", totalOutputTokens
                )
        );
    }

    @GetMapping("/chat-debug/{avatarId}")
    public Map<String, Object> debugChat(
            @AuthenticationPrincipal String userId,
            @org.springframework.web.bind.annotation.PathVariable String avatarId
    ) {
        try {
            var entities = chatRepo.findByAvatarIdOrderByCreatedAtDescRoleAsc(
                    avatarId, org.springframework.data.domain.PageRequest.of(0, 5));
            var rows = entities.stream().map(e -> Map.of(
                    "id", e.getId(),
                    "role", e.getRole() != null ? e.getRole() : "NULL",
                    "contentLen", String.valueOf(e.getContent() != null ? e.getContent().length() : 0),
                    "createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : "NULL"
            )).toList();
            return Map.of("status", "ok", "messages", rows);
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ── Centres ───────────────────────────────────────────────────────────────

    @GetMapping("/organizations")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listOrganizations() {
        List<Map<String, Object>> orgs = orgRepo.findAll().stream()
                .map(this::orgDto)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(orgs));
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        int safeSize = Math.max(1, Math.min(size, 200));
        var pageable = org.springframework.data.domain.PageRequest.of(
                Math.max(0, page), safeSize,
                org.springframework.data.domain.Sort.by("createdAt").descending());
        List<Map<String, Object>> users = userRepo.findAll(pageable).stream()
                .map(this::userDto)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(users));
    }

    // ── Invites ───────────────────────────────────────────────────────────────

    @GetMapping("/invites")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listInvites() {
        return ResponseEntity.ok(ApiResponse.success(inviteService.listInvites()));
    }

    @PostMapping("/invites")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createInvite(
            @AuthenticationPrincipal String adminUserId,
            @RequestBody Map<String, Object> body
    ) {
        return ResponseEntity.ok(ApiResponse.success(inviteService.createInvite(adminUserId, body)));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, Object> orgDto(OrganizationJpaEntity org) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", org.getId());
        m.put("name", org.getName());
        m.put("ownerUserId", org.getOwnerUserId());
        m.put("seatLimit", org.getSeatLimit());
        m.put("createdAt", org.getCreatedAt().toString());
        return m;
    }

    private Map<String, Object> userDto(UserJpaEntity u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", u.getId());
        m.put("email", u.getEmail() != null ? u.getEmail() : "");
        m.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : "");
        m.put("role", u.getRole() != null ? u.getRole() : "USER");
        m.put("isPremium", u.isPremium());
        m.put("level", u.getLevel());
        m.put("centreId", u.getCentreId());
        m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
        return m;
    }

    private double estimateCost(long haiku, long sonnet) {
        double haikuCost = haiku * (2.5 * 0.001 + 0.6 * 0.005);
        double sonnetCost = sonnet * (2.5 * 0.003 + 0.6 * 0.015);
        return Math.round((haikuCost + sonnetCost) * 100) / 100.0;
    }
}
