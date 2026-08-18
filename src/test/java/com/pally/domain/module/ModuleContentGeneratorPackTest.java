package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.infrastructure.ai.GeminiCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Proves {@link ModuleContentGenerator#generateAsPack} forces DRAFT on every item,
 * unlike {@link ModuleContentGenerator#generate} which defaults new personal-avatar
 * items to LIVE. This is the pre-moderated safety proof for syllabus_content_pack:
 * without the explicit {@code setStatus("DRAFT")} override, pack-generated content
 * would be instantly servable with zero review.
 */
@ExtendWith(MockitoExtension.class)
class ModuleContentGeneratorPackTest {

    @Mock private GeminiCompletionService geminiCompletion;
    @Mock private LearningModuleRepository moduleRepository;
    @Mock private ModuleContentItemRepository itemRepository;
    @Mock private PremiumService premiumService;
    @Mock private com.pally.domain.knowledge.groundedness.GroundednessVerifier groundednessVerifier;

    private ModuleContentGenerator generator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        generator = new ModuleContentGenerator(
                geminiCompletion, objectMapper, premiumService, groundednessVerifier,
                new ModuleWriter(moduleRepository, itemRepository, new com.pally.domain.content.PassThroughOutputValidator()));
        lenient().when(premiumService.resolveTier(anyString())).thenReturn(SubscriptionTier.FREE);
        lenient().when(groundednessVerifier.check(any(), any()))
                .thenReturn(new com.pally.domain.knowledge.groundedness.GroundednessVerifier.Report(List.of(), 0));
        when(moduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void mockAllFourGenerators() {
        when(geminiCompletion.complete(anyInt(), contains("bite-size concept cards"), eq("module-learn-gen"), anyString()))
                .thenReturn("""
                        [{"title":"Binary","body":"Base-2 number system","keyTerms":["binary"]},
                         {"title":"Denary","body":"Base-10 number system","keyTerms":["denary"]}]
                        """);
        when(geminiCompletion.complete(anyInt(), contains("true/false statements"), eq("module-hottake-gen"), anyString()))
                .thenReturn("""
                        [{"statement":"Binary uses only 0 and 1","isTrue":true,"explanation":"Correct"}]
                        """);
        when(geminiCompletion.complete(anyInt(), contains("WRONG worked solution"), eq("module-spotmistake-gen"), anyString()))
                .thenReturn("""
                        {"problem":"Convert 5 to binary","wrongSolution":"110","errorDescription":"Off by one bit","correctSolution":"101"}
                        """);
        when(geminiCompletion.complete(anyInt(), contains("application questions"), eq("module-challenge-gen"), anyString()))
                .thenReturn("""
                        [{"question":"Convert 12 to binary","answer":"1100","explanation":"8+4","difficulty":"medium"}]
                        """);
    }

    @Test
    void generateAsPack_forcesDraftStatus_onEveryItem() {
        Avatar packAvatar = Avatar.create(
                com.pally.domain.syllabus.SyllabusContentPackService.PLATFORM_SYSTEM_USER_ID,
                "SG-G3-COMPUTING-7155 / Data-and-Information", Subject.CODING, CharacterType.MOCHI);
        packAvatar.markSyllabusPack();
        WikiPage page = WikiPage.create(packAvatar.getId(), "binary-numbers", "Binary Numbers",
                "Binary is a base-2 number system using only 0 and 1.");
        mockAllFourGenerators();

        generator.generateAsPack(packAvatar, page);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModuleContentItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        List<ModuleContentItem> items = captor.getValue();

        assertThat(items).isNotEmpty();
        assertThat(items).allMatch(i -> "DRAFT".equals(i.getStatus()));
    }

    @Test
    void generate_personalAvatar_defaultsLiveStatus_forContrast() {
        // Sibling of the above: proves generate() (the path generateAsPack was copied
        // from) still defaults to LIVE for a normal personal avatar — the two methods
        // genuinely diverge on status, this isn't a no-op override.
        Avatar avatar = Avatar.create("user1", "TestAvatar", Subject.CODING, CharacterType.MOCHI);
        WikiPage page = WikiPage.create(avatar.getId(), "binary-numbers", "Binary Numbers",
                "Binary is a base-2 number system using only 0 and 1.");
        mockAllFourGenerators();

        generator.generate(avatar, page);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModuleContentItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        List<ModuleContentItem> items = captor.getValue();

        assertThat(items).isNotEmpty();
        assertThat(items).allMatch(i -> "LIVE".equals(i.getStatus()));
    }
}
