package com.pally.infrastructure.ai;

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
        log.info("[ModelRouter] Model (all tasks): {}", haiku);
        log.info("[ModelRouter] Vision standard:   {}", visionModel(visionStandard));
        log.info("[ModelRouter] Vision heavy:      {}", visionModel(visionHeavy));
        log.info("[ModelRouter] Vision max:        {}", visionModel(visionMax));
    }

    // ── Task routing (all Haiku) ──────────────────────────────────────────

    public String forChat(String ignoredUserMessage) { return haiku; }
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

    // ── Accessor ─────────────────────────────────────────────────────────

    public String getHaikuModel() { return haiku; }

    // ── Private ───────────────────────────────────────────────────────────

    private String visionModel(String override) {
        return (override != null && !override.isBlank()) ? override : haiku;
    }
}
