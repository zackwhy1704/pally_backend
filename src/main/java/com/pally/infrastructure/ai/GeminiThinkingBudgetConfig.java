package com.pally.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-purpose Gemini thinking-budget control — a fully revertible cost lever driven
 * by {@code gemini.thinking-budget.*} in application.yml (or env override).
 *
 * <p><b>Why:</b> gemini-2.5-flash runs "thinking" ON by default. Those thinking
 * tokens bill at the OUTPUT rate AND, at the low {@code maxOutputTokens} caps these
 * calls use, they eat the output budget → empty text → the empty-text throw →
 * Haiku fallback (~10× pricier). {@code FlashcardModelEvidenceGate} proved the
 * mechanism: ~40% silent page-drops on Flash until {@code thinkingBudget=0}.
 *
 * <p><b>Design (per-task, NOT global):</b>
 * <ul>
 *   <li>A purpose listed here with {@code 0} → thinking OFF. Correct for
 *       extraction / classify / structured-generation (topic-router, summarizer,
 *       class-brief, module LEARN/TEST/PROVE <em>generation</em>, wiki-compile) —
 *       strictly better: reliable output, no billed thinking tokens.</li>
 *   <li>A purpose ABSENT from the map → the caller OMITS {@code thinkingConfig}
 *       entirely → provider default (thinking ON). This is the SAFE default for
 *       REASONING purposes ({@code teach-eval}, {@code module-prove-eval}) where
 *       disabling thinking could hurt judgement quality. No reasoning purpose is
 *       flipped without the committed evidence gate (DEFERRED, Phase 1.3).</li>
 * </ul>
 *
 * This is why the map is opt-IN to thinking-off rather than opt-out: a NEW Gemini
 * caller that forgets to configure itself keeps thinking on (the safe reasoning
 * default) instead of silently losing thinking on a reasoning task.
 */
@Component
@ConfigurationProperties(prefix = "gemini")
public class GeminiThinkingBudgetConfig {

    /** purpose_label → thinkingBudget. A purpose absent from this map keeps the
     *  provider default (caller omits thinkingConfig). Bound from
     *  {@code gemini.thinking-budget.<purpose>} (kebab keys relax-map to labels). */
    private Map<String, Integer> thinkingBudget = new HashMap<>();

    public Map<String, Integer> getThinkingBudget() {
        return thinkingBudget;
    }

    public void setThinkingBudget(Map<String, Integer> thinkingBudget) {
        this.thinkingBudget = thinkingBudget == null ? new HashMap<>() : thinkingBudget;
    }

    /**
     * @return the configured thinking budget for this purpose, or {@code null} if
     *         the purpose is unlisted — in which case the caller MUST omit
     *         {@code thinkingConfig} so the provider default (thinking ON) applies.
     */
    public Integer budgetFor(String purpose) {
        return thinkingBudget.get(purpose);
    }
}
