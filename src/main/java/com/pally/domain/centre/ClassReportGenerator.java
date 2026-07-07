package com.pally.domain.centre;

import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.infrastructure.config.AiTaskExecutorConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Runs the (slow) Claude class-report generation OFF the HTTP request thread on
 * the bounded {@link AiTaskExecutorConfig#AI_TASK_EXECUTOR} pool, then persists
 * the result. Lives in its own bean (not inside {@link ClassReportService}) so
 * Spring's {@code @Async} proxy actually applies — a self-invocation from within
 * the same class would run synchronously and re-introduce the 504.
 *
 * <p>Deliberately NOT {@code @Transactional}: each store write opens its own
 * short transaction, so the ~10–20s Claude call never holds a DB connection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassReportGenerator {

    private static final int MAX_TOKENS = 700;
    private static final String REPORT_TASK = "class-report";

    private final ContentReviewService contentReviewService;
    private final ClaudeApiClient claudeClient;
    private final ModelRouter modelRouter;
    private final ClassReportStore store;
    private final com.pally.domain.content.OutputValidator outputValidator;

    /**
     * Async entry point. Always terminal: persists {@code ready} on success
     * (including the valid "no quiz data yet" state) or {@code failed} on error.
     * Never throws out — nothing awaits this method.
     */
    @Async(AiTaskExecutorConfig.AI_TASK_EXECUTOR)
    public void generate(String userId, String orgId, String classId) {
        try {
            String narrative = buildNarrative(userId, orgId, classId);
            // Quality floor (OutputValidator seam, REPORT): a blank/near-empty narrative is
            // NOT a valid report — mark it failed (retry) rather than persist empty prose the
            // teacher can't use. The valid "no quiz data yet" message clears the floor.
            if (outputValidator.retainValid(java.util.List.of(narrative),
                    com.pally.domain.content.OutputType.REPORT).isEmpty()) {
                log.warn("[ClassReport] narrative rejected as non-substantive classId={} chars={}",
                        classId, narrative.length());
                store.markFailed(classId,
                        "Report generation failed — please try again shortly.");
                return;
            }
            store.markReady(classId, narrative, Instant.now());
            log.info("[ClassReport] ready classId={} chars={}", classId, narrative.length());
        } catch (Exception e) {
            log.error("[ClassReport] generation failed classId={}: {}", classId, e.getMessage());
            store.markFailed(classId,
                    "Report generation failed — please try again shortly.");
        }
    }

    @SuppressWarnings("unchecked")
    private String buildNarrative(String userId, String orgId, String classId) {
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
        return claudeClient.complete(modelRouter.getHaikuModel(), MAX_TOKENS, prompt, REPORT_TASK);
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
        // NOTE: use .replace, NOT .formatted — the template contains a literal '%'
        // ("below 60%"), which String.format reads as a conversion specifier and
        // throws UnknownFormatConversionException on. (This was a latent bug in the
        // original synchronous service, swallowed into a 503.)
        return """
                You are an educational analytics assistant helping a teacher understand their class.
                Write a concise class performance report (4-6 short paragraphs) covering:
                1. Overall strengths — concepts the class has mastered well
                2. Weakest topics — concepts with low average mastery or many struggling students
                3. Students to watch — topics where a large fraction of students are below 60%
                4. Recommended next steps — specific, actionable suggestions for the teacher

                Class concept mastery data:
                %DATA%

                Guidelines:
                - Be specific and reference the concept names from the data
                - Keep each paragraph concise (2-3 sentences)
                - Use encouraging, professional tone
                - Write in plain English without headers or bullet points — flowing paragraphs only
                - Do NOT invent data not present above
                """.replace("%DATA%", conceptSummary);
    }
}
