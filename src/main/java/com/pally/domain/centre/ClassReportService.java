package com.pally.domain.centre;

import com.pally.domain.module.ModuleService;
import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates a class-level AI narrative report from existing analytics data.
 * Results are cached in-process for {@value #CACHE_TTL_MINUTES} minutes so
 * every teacher page-load does not burn a Claude token budget.
 *
 * <p>Scope (class-level, per the owner's decision): strengths, weakest topics,
 * students to watch, and recommended next steps. One Haiku call per cache miss.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassReportService {

    private static final int CACHE_TTL_MINUTES = 60;
    private static final int MAX_TOKENS = 700;
    private static final String REPORT_TASK = "class-report";

    private final CentreAccessService centreAccessService;
    private final ContentReviewService contentReviewService;
    private final ModuleService moduleService;
    private final ClaudeApiClient claudeClient;
    private final ModelRouter modelRouter;

    private record CachedReport(String narrative, Instant generatedAt) {}

    private final ConcurrentHashMap<String, CachedReport> cache = new ConcurrentHashMap<>();

    /**
     * Returns (generating if needed) the AI class report for the given class.
     * Auth: staff or owner of the org.
     */
    public Map<String, Object> getReport(String userId, String orgId, String classId) {
        centreAccessService.ensureStaff(userId, orgId);

        CachedReport hit = cache.get(classId);
        if (hit != null && hit.generatedAt().isAfter(Instant.now().minus(Duration.ofMinutes(CACHE_TTL_MINUTES)))) {
            return Map.of(
                    "narrative", hit.narrative(),
                    "cached", true,
                    "generatedAt", hit.generatedAt().toString()
            );
        }

        String narrative = generateNarrative(userId, orgId, classId);
        CachedReport fresh = new CachedReport(narrative, Instant.now());
        cache.put(classId, fresh);

        return Map.of(
                "narrative", narrative,
                "cached", false,
                "generatedAt", fresh.generatedAt().toString()
        );
    }

    /**
     * Evicts the cached report for a class, forcing a fresh generation on the next call.
     */
    public void evictCache(String classId) {
        cache.remove(classId);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String generateNarrative(String userId, String orgId, String classId) {
        Map<String, Object> readiness = contentReviewService.examReadiness(userId, orgId, classId);
        List<Map<String, Object>> concepts =
                readiness.get("concepts") instanceof List<?> raw
                ? (List<Map<String, Object>>) raw
                : List.of();

        if (concepts.isEmpty()) {
            return "No quiz data yet — students need to complete at least one lesson before a report can be generated.";
        }

        String conceptSummary = buildConceptSummary(concepts);
        String prompt = buildPrompt(conceptSummary);

        try {
            return claudeClient.complete(modelRouter.getHaikuModel(), MAX_TOKENS, prompt, REPORT_TASK);
        } catch (Exception e) {
            log.error("[ClassReport] Claude call failed classId={}: {}", classId, e.getMessage());
            throw new BusinessException("Report generation failed — please try again shortly.", 503);
        }
    }

    private String buildConceptSummary(List<Map<String, Object>> concepts) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> c : concepts) {
            String name = String.valueOf(c.getOrDefault("concept", "Unknown"));
            Object avg = c.getOrDefault("avgMastery", 0);
            Object below = c.getOrDefault("studentsBelowCount", 0);
            sb.append("- ").append(name)
              .append(": ").append(avg).append("% avg mastery, ")
              .append(below).append(" student(s) below 60%\n");
        }
        return sb.toString();
    }

    private String buildPrompt(String conceptSummary) {
        return """
                You are an educational analytics assistant helping a teacher understand their class.
                Write a concise class performance report (4-6 short paragraphs) covering:
                1. Overall strengths — concepts the class has mastered well
                2. Weakest topics — concepts with low average mastery or many struggling students
                3. Students to watch — topics where a large fraction of students are below 60%
                4. Recommended next steps — specific, actionable suggestions for the teacher

                Class concept mastery data:
                %s

                Guidelines:
                - Be specific and reference the concept names from the data
                - Keep each paragraph concise (2-3 sentences)
                - Use encouraging, professional tone
                - Write in plain English without headers or bullet points — flowing paragraphs only
                - Do NOT invent data not present above
                """.formatted(conceptSummary);
    }
}
