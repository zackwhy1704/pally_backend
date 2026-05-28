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
                "claude-sonnet-4-5-20241022", since);
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

    private double estimateCost(long haiku, long sonnet) {
        double haikuCost = haiku * (2.5 * 0.001 + 0.6 * 0.005);
        double sonnetCost = sonnet * (2.5 * 0.003 + 0.6 * 0.015);
        return Math.round((haikuCost + sonnetCost) * 100) / 100.0;
    }
}
