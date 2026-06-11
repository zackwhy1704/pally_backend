package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.infrastructure.ai.GeminiCompletionService;
import com.pally.infrastructure.persistence.module.LearningModuleJpaEntity;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.infrastructure.persistence.module.ModuleContentItemJpaEntity;
import com.pally.infrastructure.persistence.module.ModuleContentItemJpaRepository;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ModuleContentGeneratorTest {

    @Mock private GeminiCompletionService geminiCompletion;
    @Mock private LearningModuleJpaRepository moduleRepository;
    @Mock private ModuleContentItemJpaRepository itemRepository;
    @Mock private PremiumService premiumService;

    private ModuleContentGenerator generator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        generator = new ModuleContentGenerator(
                geminiCompletion, objectMapper, moduleRepository, itemRepository,
                premiumService);
        // Default: FREE tier for personal avatars
        lenient().when(premiumService.resolveTier(anyString()))
                .thenReturn(SubscriptionTier.FREE);
    }

    @Test
    void generate_freeAvatar_creates4MicroCards_and_2HotTakes_and_1SpotMistake_and_1Challenge() {
        Avatar avatar = Avatar.create("user1", "TestAvatar", Subject.MATHS, CharacterType.ZAP);
        WikiPage page = WikiPage.create("avatar1", "fractions", "Fractions", "Fractions are parts of a whole.");

        // Mock module save
        when(moduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // Micro cards response (FREE = 4)
        when(geminiCompletion.complete(anyInt(), contains("bite-size concept cards"), eq("module-learn-gen")))
                .thenReturn("""
                        [
                          {"title":"What is a fraction?","body":"A fraction is...","keyTerms":["fraction"],"narration_hint":"Think of a pizza"},
                          {"title":"Numerator","body":"The top number","keyTerms":["numerator"],"narration_hint":"Count the slices"},
                          {"title":"Denominator","body":"The bottom number","keyTerms":["denominator"],"narration_hint":"Total slices"},
                          {"title":"Simplifying","body":"Make it simpler","keyTerms":["simplify"],"narration_hint":"Divide both"}
                        ]
                        """);

        // Hot takes response (FREE = 2)
        when(geminiCompletion.complete(anyInt(), contains("true/false statements"), eq("module-hottake-gen")))
                .thenReturn("""
                        [
                          {"statement":"1/2 equals 0.5","isTrue":true,"explanation":"Correct"},
                          {"statement":"1/3 equals 0.5","isTrue":false,"explanation":"1/3 is about 0.333"}
                        ]
                        """);

        // Spot mistake response
        when(geminiCompletion.complete(anyInt(), contains("WRONG worked solution"), eq("module-spotmistake-gen")))
                .thenReturn("""
                        {"problem":"Add 1/2 + 1/3","wrongSolution":"2/5","errorDescription":"Added numerators and denominators","correctSolution":"5/6"}
                        """);

        // Challenges response (FREE = 1)
        when(geminiCompletion.complete(anyInt(), contains("application questions"), eq("module-challenge-gen")))
                .thenReturn("""
                        [{"question":"A pizza has 8 slices...","answer":"3/8","explanation":"3 out of 8","difficulty":"easy"}]
                        """);

        LearningModuleJpaEntity result = generator.generate(avatar, page);

        assertThat(result).isNotNull();
        assertThat(result.getStage()).isEqualTo("LEARN");
        assertThat(result.getTier()).isEqualTo("FREE");
        assertThat(result.getWikiPageSlug()).isEqualTo("fractions");

        // Verify items saved: 4 micro-cards + 2 hot-takes + 1 spot-mistake + 1 challenge = 8
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModuleContentItemJpaEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        List<ModuleContentItemJpaEntity> items = captor.getValue();
        assertThat(items).hasSize(8);

        long learnCount = items.stream().filter(i -> "LEARN".equals(i.getStage())).count();
        long testCount = items.stream().filter(i -> "TEST".equals(i.getStage())).count();
        assertThat(learnCount).isEqualTo(4);
        assertThat(testCount).isEqualTo(4); // 2 hot-takes + 1 spot-mistake + 1 challenge
    }

    @Test
    void generate_centreAvatar_createsMoreItems() {
        Avatar avatar = Avatar.create("user1", "CentreAvatar", Subject.SCIENCE, CharacterType.MOCHI);
        avatar.markCentreAvatar();
        WikiPage page = WikiPage.create("avatar2", "photosynthesis", "Photosynthesis", "Plants make food from sunlight.");

        when(moduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // Centre = 6 micro cards
        when(geminiCompletion.complete(anyInt(), contains("bite-size concept cards"), eq("module-learn-gen")))
                .thenReturn("""
                        [
                          {"title":"A","body":"...","keyTerms":[],"narration_hint":"..."},
                          {"title":"B","body":"...","keyTerms":[],"narration_hint":"..."},
                          {"title":"C","body":"...","keyTerms":[],"narration_hint":"..."},
                          {"title":"D","body":"...","keyTerms":[],"narration_hint":"..."},
                          {"title":"E","body":"...","keyTerms":[],"narration_hint":"..."},
                          {"title":"F","body":"...","keyTerms":[],"narration_hint":"..."}
                        ]
                        """);

        // Centre = 3 hot takes
        when(geminiCompletion.complete(anyInt(), contains("true/false statements"), eq("module-hottake-gen")))
                .thenReturn("""
                        [
                          {"statement":"A","isTrue":true,"explanation":"..."},
                          {"statement":"B","isTrue":false,"explanation":"..."},
                          {"statement":"C","isTrue":true,"explanation":"..."}
                        ]
                        """);

        when(geminiCompletion.complete(anyInt(), contains("WRONG worked solution"), eq("module-spotmistake-gen")))
                .thenReturn("""
                        {"problem":"Q","wrongSolution":"W","errorDescription":"E","correctSolution":"C"}
                        """);

        // Centre = 3 challenges
        when(geminiCompletion.complete(anyInt(), contains("application questions"), eq("module-challenge-gen")))
                .thenReturn("""
                        [
                          {"question":"Q1","answer":"A1","explanation":"E1","difficulty":"easy"},
                          {"question":"Q2","answer":"A2","explanation":"E2","difficulty":"medium"},
                          {"question":"Q3","answer":"A3","explanation":"E3","difficulty":"hard"}
                        ]
                        """);

        LearningModuleJpaEntity result = generator.generate(avatar, page);

        assertThat(result.getTier()).isEqualTo("CENTRE");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModuleContentItemJpaEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        List<ModuleContentItemJpaEntity> items = captor.getValue();

        // 6 micro-cards + 3 hot-takes + 1 spot-mistake + 3 challenges = 13
        assertThat(items).hasSize(13);
    }

    @Test
    void generateProveQuestions_returnsExpectedNumberOfQuestions() {
        LearningModuleJpaEntity module = new LearningModuleJpaEntity();
        module.setId("mod-1");
        module.setAvatarId("avatar-1");
        module.setTier("FREE");

        WikiPage page = WikiPage.create("avatar-1", "test-slug", "Test Topic", "Content here.");

        ModuleProgressJpaEntity p1 = new ModuleProgressJpaEntity();
        p1.setTargetConcept("concept-a");
        p1.setScore(BigDecimal.valueOf(0.3));

        when(itemRepository.countByModuleIdAndStage("mod-1", "PROVE")).thenReturn(0);
        when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // FREE = 3 prove questions
        when(geminiCompletion.complete(anyInt(), contains("prove-it questions"), eq("module-prove-gen")))
                .thenReturn("""
                        [
                          {"question":"Q1","targetConcept":"concept-a","expectedKeyPoints":["kp1"],"difficulty":"easy"},
                          {"question":"Q2","targetConcept":"concept-b","expectedKeyPoints":["kp2"],"difficulty":"medium"},
                          {"question":"Q3","targetConcept":"concept-c","expectedKeyPoints":["kp3"],"difficulty":"hard"}
                        ]
                        """);

        List<ModuleContentItemJpaEntity> result =
                generator.generateProveQuestions(module, page, List.of(p1), "FREE");

        assertThat(result).hasSize(3);
        assertThat(result).allMatch(i -> "PROVE".equals(i.getStage()));
        assertThat(result).allMatch(i -> "PROVE_QUESTION".equals(i.getType()));
    }

    @Test
    void extractJson_handlesMarkdownFencesAndPreamble() {
        String wrapped = "Here is the result:\n```json\n[{\"a\":1}]\n```";
        String result = generator.extractJson(wrapped, '[', ']');
        assertThat(result).isEqualTo("[{\"a\":1}]");
    }

    @Test
    void extractJson_returnsEmptyArrayForNull() {
        assertThat(generator.extractJson(null, '[', ']')).isEqualTo("[]");
        assertThat(generator.extractJson("", '[', ']')).isEqualTo("[]");
    }

    @Test
    void extractJson_returnsEmptyObjectForNullObject() {
        assertThat(generator.extractJson(null, '{', '}')).isEqualTo("{}");
    }
}
