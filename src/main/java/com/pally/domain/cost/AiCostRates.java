package com.pally.domain.cost;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Per-model $/million-token rates → est_cost_micros. Config-driven
 * ({@code ai.cost.rates}) so pricing updates need no deploy; sensible defaults
 * are baked so it works out of the box. Lookup matches by exact model then by
 * prefix (so a versioned Claude model like {@code claude-haiku-4-5-...} matches
 * the {@code claude-haiku} rate). Unknown model → 0 (logged by the meter).
 *
 * <p>est_cost_micros = inputTokens*inputRate + outputTokens*outputRate, where the
 * rate is USD per MILLION tokens (so tokens x rate = micros of USD directly).
 */
@Component
@ConfigurationProperties(prefix = "ai.cost")
@Getter
@Setter
public class AiCostRates {

    /** Rate for one model. input/output are USD per MILLION tokens. */
    @Getter @Setter
    public static class ModelRate {
        private String model;
        private double input;
        private double output;
        public ModelRate() {}
        public ModelRate(String model, double input, double output) {
            this.model = model; this.input = input; this.output = output;
        }
    }

    /** Overridable via config; these defaults apply when config is absent. */
    private List<ModelRate> rates = List.of(
            new ModelRate("gemini-2.5-flash-lite", 0.0375, 0.15),
            new ModelRate("gemini-2.5-flash",      0.075,  0.30),
            new ModelRate("claude-haiku",          0.80,   4.00),
            new ModelRate("claude-sonnet",         3.00,   15.00)
    );

    /** Estimated cost in micros of USD; 0 for an unknown model. */
    public long estCostMicros(String model, long inputTokens, long outputTokens) {
        ModelRate r = rateFor(model);
        if (r == null) return 0L;
        return Math.round(inputTokens * r.getInput() + outputTokens * r.getOutput());
    }

    /** Null if no rate matches — the meter logs it rather than guessing. */
    public ModelRate rateFor(String model) {
        if (model == null) return null;
        for (ModelRate r : rates) if (model.equals(r.getModel())) return r;
        // longest-prefix match so a versioned model resolves to its family rate
        ModelRate best = null;
        for (ModelRate r : rates) {
            if (r.getModel() != null && model.startsWith(r.getModel())
                    && (best == null || r.getModel().length() > best.getModel().length())) {
                best = r;
            }
        }
        return best;
    }
}
