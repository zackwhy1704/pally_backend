package com.pally.api.admin;

import com.pally.infrastructure.persistence.chat.ChatMessageJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ChatMessageJpaRepository chatRepo;

    @GetMapping("/model-usage")
    public Map<String, Object> getModelUsage(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "7") int days
    ) {
        Instant since = Instant.now().minus(Duration.ofDays(days));
        long haikuCount = chatRepo.countByModelUsedAndCreatedAtAfter(
                "claude-haiku-4-5-20251001", since);
        long sonnetCount = chatRepo.countByModelUsedAndCreatedAtAfter(
                "claude-sonnet-4-6", since);
        long total = haikuCount + sonnetCount;
        double sonnetPct = total > 0 ? (double) sonnetCount / total * 100 : 0;

        return Map.of(
                "period_days", days,
                "haiku_calls", haikuCount,
                "sonnet_calls", sonnetCount,
                "total_calls", total,
                "sonnet_percentage", Math.round(sonnetPct * 10) / 10.0,
                "estimated_cost_usd", estimateCost(haikuCount, sonnetCount)
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

    private double estimateCost(long haiku, long sonnet) {
        double haikuCost = haiku * (2.5 * 0.001 + 0.6 * 0.005);
        double sonnetCost = sonnet * (2.5 * 0.003 + 0.6 * 0.015);
        return Math.round((haikuCost + sonnetCost) * 100) / 100.0;
    }
}
