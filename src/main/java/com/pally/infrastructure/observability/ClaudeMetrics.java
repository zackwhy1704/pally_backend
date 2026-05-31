package com.pally.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Per-call Claude metrics — the margin North Star for this app.
 * pally.claude.tokens is the dollars-against-revenue counter; latency
 * and errors are the SRE signals. All emitted to the Micrometer
 * registry that the Prometheus actuator scrapes.
 *
 * <p>Tagged with {@code task} (chat | compile | quiz | relevance | etc.)
 * so dashboards can split spend by feature and catch a regression
 * (e.g. a wiki-compile blow-up) before the bill arrives.
 */
@Component
@RequiredArgsConstructor
public class ClaudeMetrics {

    private final MeterRegistry registry;

    public void recordTokens(String task, String model, long input, long output) {
        registry.counter("pally.claude.tokens",
                "task", safe(task), "model", safe(model), "direction", "input")
                .increment(input);
        registry.counter("pally.claude.tokens",
                "task", safe(task), "model", safe(model), "direction", "output")
                .increment(output);
    }

    public Timer.Sample startLatency() {
        return Timer.start(registry);
    }

    public void stopLatency(Timer.Sample sample, String task, String model) {
        sample.stop(registry.timer("pally.claude.latency",
                "task", safe(task), "model", safe(model)));
    }

    public void recordError(String task, String type) {
        registry.counter("pally.claude.errors",
                "task", safe(task), "type", safe(type)).increment();
    }

    @SuppressWarnings("unused")
    public void recordRaw(String task, String model, Duration d) {
        registry.timer("pally.claude.latency",
                "task", safe(task), "model", safe(model)).record(d);
    }

    // ── AI-quality / tool-use observability ──────────────────────────────────

    public void recordToolCall(String toolName) {
        registry.counter("pally.ai.tool.calls", "tool", safe(toolName)).increment();
    }

    public void recordToolError(String toolName) {
        registry.counter("pally.ai.tool.errors", "tool", safe(toolName)).increment();
    }

    /**
     * Fired when the calculator disagrees with the model's stated answer.
     * A rising rate = model hallucinating arithmetic = direct quality signal.
     */
    public void recordCalculatorDisagreement(String surface) {
        registry.counter("pally.ai.tool.disagreement",
                "tool", "calculator", "surface", safe(surface)).increment();
    }

    /** Fired when a quiz question's correctIndex fails calculator verification. */
    public void recordQuizAnswerDisagreement() {
        registry.counter("pally.quiz.answer.disagreement").increment();
    }

    private String safe(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
