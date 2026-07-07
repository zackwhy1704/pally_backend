package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.infrastructure.ai.GeminiCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests that subscription tier resolution correctly maps to content depth tier.
 * Pro/Max/Family subscribers get CENTRE-depth content (full);
 * Free subscribers get FREE-depth content (limited).
 */
@ExtendWith(MockitoExtension.class)
class ModuleContentGeneratorTierTest {

    @Mock private GeminiCompletionService geminiCompletion;
    @Mock private LearningModuleRepository moduleRepository;
    @Mock private ModuleContentItemRepository itemRepository;
    @Mock private PremiumService premiumService;
    @Mock private com.pally.domain.knowledge.groundedness.GroundednessVerifier groundednessVerifier;

    private ModuleContentGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ModuleContentGenerator(
                geminiCompletion, new ObjectMapper(), premiumService, groundednessVerifier,
                new ModuleWriter(moduleRepository, itemRepository, new com.pally.domain.content.PassThroughOutputValidator()));
    }

    @Test
    void resolveContentTier_centreAvatar_alwaysCentre() {
        Avatar avatar = Avatar.create("user-1", "CentreBot", Subject.SCIENCE, CharacterType.MOCHI);
        avatar.markCentreAvatar();

        String tier = generator.resolveContentTier(avatar);
        assertThat(tier).isEqualTo("CENTRE");
    }

    @Test
    void resolveContentTier_freeSubscriber_returnsFree() {
        Avatar avatar = Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.ZAP);
        when(premiumService.resolveTier("user-1")).thenReturn(SubscriptionTier.FREE);

        String tier = generator.resolveContentTier(avatar);
        assertThat(tier).isEqualTo("FREE");
    }

    @Test
    void resolveContentTier_proSubscriber_returnsCentre() {
        Avatar avatar = Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.ZAP);
        when(premiumService.resolveTier("user-1")).thenReturn(SubscriptionTier.PRO);

        String tier = generator.resolveContentTier(avatar);
        assertThat(tier).isEqualTo("CENTRE");
    }

    @Test
    void resolveContentTier_maxSubscriber_returnsCentre() {
        Avatar avatar = Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.ZAP);
        when(premiumService.resolveTier("user-1")).thenReturn(SubscriptionTier.MAX);

        String tier = generator.resolveContentTier(avatar);
        assertThat(tier).isEqualTo("CENTRE");
    }

    @Test
    void resolveContentTier_familySubscriber_returnsCentre() {
        Avatar avatar = Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.ZAP);
        when(premiumService.resolveTier("user-1")).thenReturn(SubscriptionTier.FAMILY);

        String tier = generator.resolveContentTier(avatar);
        assertThat(tier).isEqualTo("CENTRE");
    }

    @Test
    void resolveContentTier_premiumServiceThrows_defaultsToFree() {
        Avatar avatar = Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.ZAP);
        when(premiumService.resolveTier("user-1")).thenThrow(new RuntimeException("DB down"));

        String tier = generator.resolveContentTier(avatar);
        assertThat(tier).isEqualTo("FREE");
    }
}
