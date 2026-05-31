package com.pally.infrastructure.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Centralises model selection. Two routing dimensions:
 * <ol>
 *   <li>Task routing: Haiku for cheap tasks; Sonnet for quality-sensitive ones.</li>
 *   <li>Visual-math escalation (Tier 2): three-tier vision ladder configurable via
 *       env vars so escalation can be disabled/capped for cost control.</li>
 * </ol>
 * Escalation is ALWAYS layered on top of Tier 0/1 (disclaim + confirm-values).
 */
@Component
@Slf4j
public class ModelRouter {

    @Value("${claude.api.model}")
    private String haiku;

    @Value("${claude.api.sonnet-model:claude-sonnet-4-6}")
    private String sonnet;

    // Vision escalation ladder — any tier can equal haiku/sonnet to disable escalation.
    @Value("${claude.model.vision-standard:#{null}}")
    private String visionStandard;  // null → haiku

    @Value("${claude.model.vision-heavy:#{null}}")
    private String visionHeavy;     // null → sonnet

    @Value("${claude.model.vision-max:#{null}}")
    private String visionMax;       // null → sonnet (set to Opus for full escalation)

    @jakarta.annotation.PostConstruct
    void validate() {
        log.info("[ModelRouter] Haiku:          {}", haiku);
        log.info("[ModelRouter] Sonnet:         {}", sonnet);
        if (sonnet == null || sonnet.isBlank()) {
            log.warn("[ModelRouter] sonnet-model blank — falling back to Haiku");
            sonnet = haiku;
        }
        log.info("[ModelRouter] Vision standard: {}", visionStandardModel());
        log.info("[ModelRouter] Vision heavy:    {}", visionHeavyModel());
        log.info("[ModelRouter] Vision max:      {}", visionMaxModel());
    }

    // ── Standard task routing ─────────────────────────────────────────────────

    public String forChat(String userMessage) {
        if (isComplexQuestion(userMessage)) {
            log.info("[ModelRouter] SONNET for complex chat: \"{}\"",
                    userMessage.substring(0, Math.min(80, userMessage.length())));
            return sonnet;
        }
        return haiku;
    }

    public String forWikiCompile()    { return sonnet; }
    public String forQuizGeneration() { return sonnet; }
    public String forRelevanceCheck() { return haiku; }
    public String forCacheKeepalive() { return haiku; }

    // ── Vision escalation ladder ──────────────────────────────────────────────

    /** Non-visual / plain-text photo questions. Routes to vision-standard (default: Haiku). */
    public String forPhotoQuestion() { return visionStandardModel(); }

    /** Simple visual (TABLE, CHART, MEDIUM confidence). Routes to vision-heavy (default: Sonnet). */
    public String forPhotoQuestionHeavy() { return visionHeavyModel(); }

    /** Hard visual (GRAPH, GEOMETRY) or LOW confidence. Routes to vision-max (default: Sonnet). */
    public String forPhotoQuestionMax() { return visionMaxModel(); }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getHaikuModel()  { return haiku; }
    public String getSonnetModel() { return sonnet; }

    // ── Private ───────────────────────────────────────────────────────────────

    private String visionStandardModel() {
        return (visionStandard != null && !visionStandard.isBlank()) ? visionStandard : haiku;
    }

    private String visionHeavyModel() {
        return (visionHeavy != null && !visionHeavy.isBlank()) ? visionHeavy : sonnet;
    }

    private String visionMaxModel() {
        return (visionMax != null && !visionMax.isBlank()) ? visionMax : sonnet;
    }

    private boolean isComplexQuestion(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = message.toLowerCase().trim();
        if (lower.length() > 200) return true;
        String[] complex = {
            "explain why", "explain how", "prove that", "prove this",
            "compare and contrast", "what's the difference between",
            "what is the difference between",
            "step by step", "show your work", "show me how",
            "how does", "how do", "how would",
            "derive", "analyze", "analyse", "evaluate",
            "calculate", "solve for", "find the value",
            "what would happen if", "what if",
            "in what way", "to what extent",
            "critically", "justify", "argue",
        };
        for (String kw : complex) { if (lower.contains(kw)) return true; }
        if (lower.startsWith("why ")) return true;
        if (lower.matches(".*\\d+\\s*[+\\-*/×÷=]\\s*\\d+.*")) return true;
        return false;
    }
}
