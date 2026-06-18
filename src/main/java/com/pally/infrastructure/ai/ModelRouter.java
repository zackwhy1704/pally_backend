package com.pally.infrastructure.ai;

import com.pally.domain.subscription.SubscriptionTier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Centralises model selection. Every call uses Haiku — Sonnet has been
 * permanently removed. Cost breakdown at launch:
 * <ul>
 *   <li>Haiku input: $0.80/M tokens</li>
 *   <li>Haiku output: $4.00/M tokens</li>
 *   <li>Sonnet was ~15x more expensive; removed entirely.</li>
 * </ul>
 *
 * <p>Vision: Haiku 4.5 supports vision (images). All photo-question tiers
 * default to Haiku. The three-tier ladder is preserved via env vars so we
 * can plug in a cheaper/faster model in future without code changes.
 */
@Component
@Slf4j
public class ModelRouter {

    @Value("${claude.api.model}")
    private String haiku;

    /// Sonnet model ID used for Max/Family/Centre tier on complex questions.
    /// Defaults to claude-sonnet-4-5 but can be overridden in Railway.
    @Value("${claude.model.sonnet:claude-sonnet-4-5}")
    private String sonnet;

    // Vision escalation ladder — override per tier via Railway env vars:
    //   CLAUDE_VISION_STANDARD / CLAUDE_VISION_HEAVY / CLAUDE_VISION_MAX
    // All default to Haiku. Set to a different Anthropic model ID if a
    // specific tier needs higher quality (e.g. geometry diagram parsing).
    @Value("${claude.model.vision-standard:#{null}}")
    private String visionStandard;

    @Value("${claude.model.vision-heavy:#{null}}")
    private String visionHeavy;

    @Value("${claude.model.vision-max:#{null}}")
    private String visionMax;

    @jakarta.annotation.PostConstruct
    void log() {
        log.info("[ModelRouter] Haiku model (default): {}", haiku);
        log.info("[ModelRouter] Sonnet model (Max/Family/Centre complex): {}", sonnet);
        log.info("[ModelRouter] Vision standard:   {}", visionModel(visionStandard));
        log.info("[ModelRouter] Vision heavy:      {}", visionModel(visionHeavy));
        log.info("[ModelRouter] Vision max:        {}", visionModel(visionMax));
    }

    // ── Task routing ──────────────────────────────────────────────────────

    /**
     * Selects the chat model for the given user message and subscription tier.
     *
     * <p>MAX / FAMILY users are routed to Sonnet for complex questions
     * so they get meaningfully better answers on hard maths/science problems.
     * FREE and PRO users always get Haiku (cost control).
     */
    public String forChat(String userMessage, SubscriptionTier tier) {
        if ((tier == SubscriptionTier.MAX || tier == SubscriptionTier.FAMILY)
                && isComplexQuestion(userMessage)) {
            log.info("[ModelRouter] SONNET for Max-tier complex chat: \"{}\"",
                    userMessage == null ? "" :
                    userMessage.substring(0, Math.min(60, userMessage.length())));
            return sonnet;
        }
        return haiku;
    }

    /**
     * Centre-sourced override: always returns Haiku regardless of tier.
     * Belt-and-suspenders guard so centre students never accidentally
     * get Sonnet even if a future tier mapping changes.
     */
    public String forChat(String userMessage, SubscriptionTier tier, boolean isCentreSourced) {
        if (isCentreSourced) return haiku;
        return forChat(userMessage, tier);
    }

    /**
     * Backward-compatible overload — treats as FREE tier (always Haiku).
     * Used by code paths that don't yet have a tier context.
     */
    public String forChat(String userMessage) { return haiku; }
    public String forWikiCompile()    { return haiku; }
    public String forQuizGeneration() { return haiku; }
    public String forRelevanceCheck() { return haiku; }
    public String forCacheKeepalive() { return haiku; }

    // ── Vision (photo homework scan) ─────────────────────────────────────

    /** Plain-text photo questions. */
    public String forPhotoQuestion()      { return visionModel(visionStandard); }
    /** TABLE, CHART, MEDIUM-confidence visual. */
    public String forPhotoQuestionHeavy() { return visionModel(visionHeavy); }
    /** GRAPH, GEOMETRY, LOW-confidence visual. */
    public String forPhotoQuestionMax()   { return visionModel(visionMax); }

    // ── Accessors ────────────────────────────────────────────────────────

    public String getHaikuModel()  { return haiku; }
    public String getSonnetModel() { return sonnet; }

    // ── Private ───────────────────────────────────────────────────────────

    private String visionModel(String override) {
        return (override != null && !override.isBlank()) ? override : haiku;
    }

    /**
     * Heuristic: returns true if the question is likely to benefit from Sonnet's
     * stronger reasoning. Gated behind Max tier so cost impact is bounded.
     *
     * <p>Triggers: long messages (>300 chars), multi-step arithmetic patterns,
     * or presence of known "deep reasoning" keywords.
     */
    private boolean isComplexQuestion(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = message.toLowerCase().trim();
        if (lower.length() > 300) return true;
        String[] complexKeywords = {
            "prove", "derive", "integrate", "differentiate", "explain why",
            "compare and contrast", "step by step", "show working",
            "evaluate", "critically", "in what way", "justify",
            "how does", "what would happen if",
        };
        for (String kw : complexKeywords) {
            if (lower.contains(kw)) return true;
        }
        // Multi-step arithmetic: "3 * 4 + 5" style patterns
        return lower.matches(".*\\d+\\s*[*/÷×]\\s*\\d+.*\\d+.*");
    }
}
