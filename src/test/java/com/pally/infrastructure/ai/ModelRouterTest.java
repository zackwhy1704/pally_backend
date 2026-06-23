package com.pally.infrastructure.ai;

import com.pally.domain.subscription.SubscriptionTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CA-10 guard: a centre-sourced request must resolve to Haiku on the vision path,
 * even when CLAUDE_VISION_* overrides point at a premium model. Mirrors the chat
 * guard. Non-centre tiers keep their configured model.
 */
class ModelRouterTest {

    private ModelRouter router;

    @BeforeEach
    void setUp() {
        router = new ModelRouter();
        ReflectionTestUtils.setField(router, "haiku", "claude-haiku");
        ReflectionTestUtils.setField(router, "sonnet", "claude-sonnet");
        // Simulate an operator pointing the vision overrides at premium models.
        ReflectionTestUtils.setField(router, "visionStandard", "vision-std-premium");
        ReflectionTestUtils.setField(router, "visionHeavy", "vision-heavy-premium");
        ReflectionTestUtils.setField(router, "visionMax", "vision-max-premium");
    }

    @Test
    void forPhotoQuestionMax_centreSourced_forcesHaiku_evenWhenVisionMaxIsPremium() {
        assertThat(router.forPhotoQuestionMax()).isEqualTo("vision-max-premium");        // tier path unchanged
        assertThat(router.forPhotoQuestionMax(false)).isEqualTo("vision-max-premium");   // non-centre unchanged
        assertThat(router.forPhotoQuestionMax(true)).isEqualTo("claude-haiku");          // centre → Haiku
    }

    @Test
    void forPhotoQuestion_heavyAndStandard_centreSourced_forceHaiku() {
        assertThat(router.forPhotoQuestionHeavy(true)).isEqualTo("claude-haiku");
        assertThat(router.forPhotoQuestion(true)).isEqualTo("claude-haiku");
        assertThat(router.forPhotoQuestionHeavy(false)).isEqualTo("vision-heavy-premium");
        assertThat(router.forPhotoQuestion(false)).isEqualTo("vision-std-premium");
    }

    @Test
    void forChat_centreSourced_forcesHaiku_evenForMaxTier() {
        // Regression: the original chat guard still holds.
        assertThat(router.forChat("explain why integration works in detail",
                SubscriptionTier.MAX, true)).isEqualTo("claude-haiku");
    }
}
