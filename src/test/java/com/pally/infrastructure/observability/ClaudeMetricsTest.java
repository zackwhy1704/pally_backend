package com.pally.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeMetricsTest {

    private SimpleMeterRegistry registry;
    private ClaudeMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ClaudeMetrics(registry);
    }

    @Test
    void recordUserCost_incrementsCounterWithCorrectTags() {
        metrics.recordUserCost("user-1", "chat", "claude-sonnet-4", 1000, 500);

        Counter counter = registry.find("pally.user.ai_cost")
                .tag("user_id", "user-1")
                .tag("task", "chat")
                .tag("model", "claude-sonnet-4")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThan(0);
    }

    @Test
    void recordUserCost_handlesNullUserId() {
        metrics.recordUserCost(null, "compile", "haiku", 500, 200);

        Counter counter = registry.find("pally.user.ai_cost")
                .tag("user_id", "unknown")
                .tag("task", "compile")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThan(0);
    }

    @Test
    void recordUserCost_sonnetCostsMoreThanHaiku() {
        metrics.recordUserCost("u1", "chat", "sonnet", 10000, 5000);
        double sonnetCost = registry.find("pally.user.ai_cost")
                .tag("model", "sonnet").counter().count();

        metrics.recordUserCost("u1", "chat", "haiku", 10000, 5000);
        double haikuCost = registry.find("pally.user.ai_cost")
                .tag("model", "haiku").counter().count();

        assertThat(sonnetCost).isGreaterThan(haikuCost);
    }

    @Test
    void recordUserCost_geminiPricingApplied() {
        metrics.recordUserCost("u1", "quiz", "gemini-2.0-flash", 1000, 500);

        Counter counter = registry.find("pally.user.ai_cost")
                .tag("model", "gemini-2.0-flash")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThan(0);
    }

    @Test
    void recordTokens_incrementsInputAndOutputCounters() {
        metrics.recordTokens("chat", "sonnet", 100, 50);

        Counter input = registry.find("pally.claude.tokens")
                .tag("direction", "input")
                .tag("task", "chat")
                .counter();
        Counter output = registry.find("pally.claude.tokens")
                .tag("direction", "output")
                .tag("task", "chat")
                .counter();

        assertThat(input).isNotNull();
        assertThat(input.count()).isEqualTo(100);
        assertThat(output).isNotNull();
        assertThat(output.count()).isEqualTo(50);
    }
}
