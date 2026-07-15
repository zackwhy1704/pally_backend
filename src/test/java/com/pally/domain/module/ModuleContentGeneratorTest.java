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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModuleContentGeneratorTest {

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
        // Default: FREE tier for personal avatars
        lenient().when(premiumService.resolveTier(anyString()))
                .thenReturn(SubscriptionTier.FREE);
        // Groundedness gate: clean by default (no flags) — keeps existing assertions.
        lenient().when(groundednessVerifier.check(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.pally.domain.knowledge.groundedness.GroundednessVerifier.Report(
                        java.util.List.of(), 0));
    }

    private LearningModule proveModule(String id) {
        LearningModule m = new LearningModule();
        m.setId(id);
        m.setAvatarId("av-1");
        m.setWikiPageSlug("dividing-fractions");
        return m;
    }

    @Test
    void generateProveQuestions_parsesProseAndMarkdownWrappedArray() {
        // The real-world failure: model wraps the array in prose + ```json fences.
        LearningModule m = proveModule("mod-1");
        WikiPage page = WikiPage.create("av-1", "dividing-fractions", "Dividing Fractions", "keep change flip");
        when(itemRepository.countByModuleIdAndStage(anyString(), anyString())).thenReturn(0);
        when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-gen"), anyString()))
                .thenReturn("Sure! Here are the questions:\n```json\n"
                        + "[{\"question\":\"Divide 1/2 by 1/4\",\"targetConcept\":\"dividing fractions\","
                        + "\"expectedKeyPoints\":[\"keep change flip\"],\"difficulty\":\"medium\"}]\n```");

        List<ModuleContentItem> items = generator.generateProveQuestions(m, page, List.of(), "FREE");

        assertThat(items).isNotEmpty();
        assertThat(items.get(0).getStage()).isEqualTo("PROVE");
    }

    @Test
    void generateProveQuestions_fallsBackToReinforcement_soModuleCanComplete() {
        // Model returns prose with NO array (both attempts) — must still yield >=1
        // item so the module is completable (student not stuck; weakness trigger fires).
        LearningModule m = proveModule("mod-2");
        WikiPage page = WikiPage.create("av-1", "dividing-fractions", "Dividing Fractions", "keep change flip");
        when(itemRepository.countByModuleIdAndStage(anyString(), anyString())).thenReturn(0);
        when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-gen"), anyString()))
                .thenReturn("The student did great — no questions needed.");

        List<ModuleContentItem> items = generator.generateProveQuestions(m, page, List.of(), "FREE");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getStage()).isEqualTo("PROVE");
        assertThat(items.get(0).getContentJson()).contains("Dividing Fractions");
    }

    @Test
    void generate_freeAvatar_creates4MicroCards_and_2HotTakes_and_1SpotMistake_and_1Challenge() {
        Avatar avatar = Avatar.create("user1", "TestAvatar", Subject.MATHS, CharacterType.ZAP);
        WikiPage page = WikiPage.create("avatar1", "fractions", "Fractions", "Fractions are parts of a whole.");

        // Mock module save
        when(moduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // Micro cards response (FREE = 4)
        when(geminiCompletion.complete(anyInt(), contains("bite-size concept cards"), eq("module-learn-gen"), anyString()))
                .thenReturn("""
                        [
                          {"title":"What is a fraction?","body":"A fraction is...","keyTerms":["fraction"]},
                          {"title":"Numerator","body":"The top number","keyTerms":["numerator"]},
                          {"title":"Denominator","body":"The bottom number","keyTerms":["denominator"]},
                          {"title":"Simplifying","body":"Make it simpler","keyTerms":["simplify"]}
                        ]
                        """);

        // Hot takes response (FREE = 2)
        when(geminiCompletion.complete(anyInt(), contains("true/false statements"), eq("module-hottake-gen"), anyString()))
                .thenReturn("""
                        [
                          {"statement":"1/2 equals 0.5","isTrue":true,"explanation":"Correct"},
                          {"statement":"1/3 equals 0.5","isTrue":false,"explanation":"1/3 is about 0.333"}
                        ]
                        """);

        // Spot mistake response
        when(geminiCompletion.complete(anyInt(), contains("WRONG worked solution"), eq("module-spotmistake-gen"), anyString()))
                .thenReturn("""
                        {"problem":"Add 1/2 + 1/3","wrongSolution":"2/5","errorDescription":"Added numerators and denominators","correctSolution":"5/6"}
                        """);

        // Challenges response (FREE = 1)
        when(geminiCompletion.complete(anyInt(), contains("application questions"), eq("module-challenge-gen"), anyString()))
                .thenReturn("""
                        [{"question":"A pizza has 8 slices...","answer":"3/8","explanation":"3 out of 8","difficulty":"easy"}]
                        """);

        LearningModule result = generator.generate(avatar, page);

        assertThat(result).isNotNull();
        assertThat(result.getStage()).isEqualTo("LEARN");
        assertThat(result.getTier()).isEqualTo("FREE");
        assertThat(result.getWikiPageSlug()).isEqualTo("fractions");

        // Verify items saved: 4 micro-cards + 2 hot-takes + 1 spot-mistake + 1 challenge = 8
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModuleContentItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        List<ModuleContentItem> items = captor.getValue();
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
        when(geminiCompletion.complete(anyInt(), contains("bite-size concept cards"), eq("module-learn-gen"), anyString()))
                .thenReturn("""
                        [
                          {"title":"A","body":"...","keyTerms":[]},
                          {"title":"B","body":"...","keyTerms":[]},
                          {"title":"C","body":"...","keyTerms":[]},
                          {"title":"D","body":"...","keyTerms":[]},
                          {"title":"E","body":"...","keyTerms":[]},
                          {"title":"F","body":"...","keyTerms":[]}
                        ]
                        """);

        // Centre = 3 hot takes
        when(geminiCompletion.complete(anyInt(), contains("true/false statements"), eq("module-hottake-gen"), anyString()))
                .thenReturn("""
                        [
                          {"statement":"A","isTrue":true,"explanation":"..."},
                          {"statement":"B","isTrue":false,"explanation":"..."},
                          {"statement":"C","isTrue":true,"explanation":"..."}
                        ]
                        """);

        when(geminiCompletion.complete(anyInt(), contains("WRONG worked solution"), eq("module-spotmistake-gen"), anyString()))
                .thenReturn("""
                        {"problem":"Q","wrongSolution":"W","errorDescription":"E","correctSolution":"C"}
                        """);

        // Centre = 3 challenges
        when(geminiCompletion.complete(anyInt(), contains("application questions"), eq("module-challenge-gen"), anyString()))
                .thenReturn("""
                        [
                          {"question":"Q1","answer":"A1","explanation":"E1","difficulty":"easy"},
                          {"question":"Q2","answer":"A2","explanation":"E2","difficulty":"medium"},
                          {"question":"Q3","answer":"A3","explanation":"E3","difficulty":"hard"}
                        ]
                        """);

        LearningModule result = generator.generate(avatar, page);

        assertThat(result.getTier()).isEqualTo("CENTRE");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModuleContentItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        List<ModuleContentItem> items = captor.getValue();

        // 6 micro-cards + 3 hot-takes + 1 spot-mistake + 3 challenges = 13
        assertThat(items).hasSize(13);
    }

    @Test
    void generateProveQuestions_returnsExpectedNumberOfQuestions() {
        LearningModule module = new LearningModule();
        module.setId("mod-1");
        module.setAvatarId("avatar-1");
        module.setTier("FREE");

        WikiPage page = WikiPage.create("avatar-1", "test-slug", "Test Topic", "Content here.");

        ModuleProgress p1 = new ModuleProgress();
        p1.setTargetConcept("concept-a");
        p1.setScore(BigDecimal.valueOf(0.3));

        when(itemRepository.countByModuleIdAndStage("mod-1", "PROVE")).thenReturn(0);
        when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // FREE = 3 prove questions
        when(geminiCompletion.complete(anyInt(), contains("prove-it questions"), eq("module-prove-gen"), anyString()))
                .thenReturn("""
                        [
                          {"question":"Q1","targetConcept":"concept-a","expectedKeyPoints":["kp1"],"difficulty":"easy"},
                          {"question":"Q2","targetConcept":"concept-b","expectedKeyPoints":["kp2"],"difficulty":"medium"},
                          {"question":"Q3","targetConcept":"concept-c","expectedKeyPoints":["kp3"],"difficulty":"hard"}
                        ]
                        """);

        List<ModuleContentItem> result =
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

    // ── B2: truncated micro-card JSON recovers complete elements ────────────────

    @Test
    void parseJsonObjectsLenient_recoversCompleteElementsFromTruncatedArray() {
        // The whole-array parse fails (no closing ]); complete objects are salvaged.
        String truncated = "[{\"title\":\"A\",\"body\":\"x\"},{\"title\":\"B\",\"body\":\"y\"},{\"title\":\"C\",\"bod";
        List<java.util.Map<String, Object>> items = generator.parseJsonObjectsLenient(truncated);
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("title", "A");
        assertThat(items.get(1)).containsEntry("title", "B");
    }

    @Test
    void parseJsonObjectsLenient_parsesACleanArrayFully() {
        String clean = "[{\"title\":\"A\"},{\"title\":\"B\"},{\"title\":\"C\"}]";
        assertThat(generator.parseJsonObjectsLenient(clean)).hasSize(3);
    }

    @Test
    void extractBalancedObjects_handlesNestingAndStringBraces_skipsTruncatedTail() {
        // A brace inside a string must not close the object; the truncated 3rd is skipped.
        String s = "[{\"a\":{\"n\":1},\"s\":\"has } brace\"},{\"b\":2},{\"c\":";
        assertThat(com.pally.shared.json.JsonExtraction.extractBalancedObjects(s)).hasSize(2);
    }

    // ── FIX C: persona fallback + FIX D: student-facing feedback prompts ─────────

    /** Runs generate() and returns every prompt string sent to the model. */
    private List<String> capturePrompts(Avatar avatar) {
        WikiPage page = WikiPage.create("avatar1", "sales", "Closing the Sale",
                "A good salesperson listens before pitching.");
        lenient().when(moduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        // Any prompt → empty parse → fallback item; the PROMPT is still sent + captured.
        lenient().when(geminiCompletion.complete(anyInt(), anyString(), anyString(), anyString()))
                .thenReturn("[]");
        generator.generate(avatar, page);
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(geminiCompletion, atLeastOnce())
                .complete(anyInt(), cap.capture(), anyString(), anyString());
        return cap.getAllValues();
    }

    private String promptContaining(List<String> prompts, String needle) {
        return prompts.stream().filter(p -> p.contains(needle)).findFirst()
                .orElseThrow(() -> new AssertionError("no prompt containing: " + needle));
    }

    @Test
    void nullGradeLevel_usesNeutralPersona_neverPrimarySchool() {
        // The bug: a null gradeLevel children-framed ALL content (a sales book → kids).
        Avatar avatar = Avatar.create("user1", "Sales", Subject.GENERAL, CharacterType.MOCHI);
        assertThat(avatar.getGradeLevel()).isNull();

        List<String> prompts = capturePrompts(avatar);

        assertThat(prompts).isNotEmpty();
        assertThat(prompts).noneMatch(p -> p.contains("primary school student")); // persona framing; "primary school" alone now appears only in the FIX E leak-guard examples
        // Every generator frames to the neutral "a student".
        assertThat(promptContaining(prompts, "bite-size concept cards"))
                .contains("for a student studying");
        assertThat(promptContaining(prompts, "true/false statements")).contains("for a student.");
        assertThat(promptContaining(prompts, "application questions"))
                .contains("test whether a student can USE");
        assertThat(promptContaining(prompts, "WRONG worked solution"))
                .contains("misconception a student would make");
    }

    @Test
    void setGradeLevel_isUnchanged_framesAsThatGradeStudent() {
        // Behavior MUST be identical to before when a grade is set.
        Avatar avatar = Avatar.create("user1", "Sci", Subject.SCIENCE, CharacterType.MOCHI);
        avatar.setGradeLevel("P5");

        List<String> prompts = capturePrompts(avatar);

        assertThat(promptContaining(prompts, "true/false statements")).contains("for a P5 student.");
        assertThat(prompts).noneMatch(p -> p.contains("primary school student")); // persona framing; "primary school" alone now appears only in the FIX E leak-guard examples
    }

    @Test
    void explanationPrompts_specifyStudentFacingFeedback_notRubric() {
        // FIX D: the model wrote assessor meta ("This question assesses…") which we serve
        // to students. Each explanation-bearing prompt now specifies direct feedback.
        Avatar avatar = Avatar.create("user1", "Sales", Subject.GENERAL, CharacterType.MOCHI);
        List<String> prompts = capturePrompts(avatar);

        // Collapse whitespace: the instruction phrases wrap across text-block lines.
        String hotTake = promptContaining(prompts, "true/false statements").replaceAll("\\s+", " ");
        assertThat(hotTake).contains("shown to the learner AFTER")
                .contains("Do NOT describe what the question tests");

        String challenge = promptContaining(prompts, "application questions").replaceAll("\\s+", " ");
        assertThat(challenge).contains("shown to the learner AFTER")
                .contains("Do NOT describe what the question assesses");

        String spot = promptContaining(prompts, "WRONG worked solution").replaceAll("\\s+", " ");
        assertThat(spot).contains("shown to the learner AFTER")
                .contains("Do NOT describe what the exercise tests");
    }

    @Test
    void spotMistakePrompt_guardsProblemAndWrongSolutionPurity_noMetaOrErrorHints() {
        // The problem/wrongSolution must be pure student-facing content — no labels,
        // meta-commentary, or hints at the planted error (which would defeat "find it").
        Avatar avatar = Avatar.create("user1", "Sales", Subject.GENERAL, CharacterType.MOCHI);
        String spot = promptContaining(capturePrompts(avatar), "WRONG worked solution")
                .replaceAll("\\s+", " ");
        assertThat(spot)
                .contains("\"problem\" and \"wrongSolution\" must contain ONLY")
                .contains("NOT name or hint at the error");
    }

    /** FIX E: the persona sets vocabulary/difficulty ONLY — the generated text must never
     * leak the reader's grade/age/schooling level. Guard present in every gen prompt. */
    private static final String LEAK_GUARD =
            "NEVER name, quote, or allude to the reader's grade, age, or schooling level";

    @Test
    void everyGenPrompt_carriesPersonaLeakGuard() {
        Avatar avatar = Avatar.create("user1", "Sales", Subject.GENERAL, CharacterType.MOCHI);
        List<String> prompts = capturePrompts(avatar);
        for (String anchor : List.of("bite-size concept cards", "true/false statements",
                "WRONG worked solution", "application questions")) {
            assertThat(promptContaining(prompts, anchor).replaceAll("\\s+", " "))
                    .as("persona-leak guard in the %s prompt", anchor)
                    .contains(LEAK_GUARD);
        }
    }

    @Test
    void provePrompt_carriesPersonaLeakGuard() {
        LearningModule m = proveModule("mod-e");
        WikiPage page = WikiPage.create("av-1", "dividing-fractions", "Dividing Fractions",
                "keep change flip");
        when(itemRepository.countByModuleIdAndStage(anyString(), anyString())).thenReturn(0);
        lenient().when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-gen"), anyString()))
                .thenReturn("[]");

        generator.generateProveQuestions(m, page, List.of(), "FREE");

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(geminiCompletion, atLeastOnce())
                .complete(anyInt(), cap.capture(), eq("module-prove-gen"), anyString());
        assertThat(cap.getValue().replaceAll("\\s+", " ")).contains(LEAK_GUARD);
    }

    @Test
    void provePrompt_injectsStudyMaterialAndGroundingInstruction() {
        // FIX F: PROVE previously built questions from the page TITLE + test scores only —
        // ungrounded. The prompt must now carry the material + an only-from-material rule.
        LearningModule m = proveModule("mod-f");
        WikiPage page = WikiPage.create("av-1", "dividing-fractions", "Dividing Fractions",
                "To divide fractions: keep, change, flip.");
        when(itemRepository.countByModuleIdAndStage(anyString(), anyString())).thenReturn(0);
        lenient().when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-gen"), anyString()))
                .thenReturn("[]");

        generator.generateProveQuestions(m, page, List.of(), "FREE");

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(geminiCompletion, atLeastOnce())
                .complete(anyInt(), cap.capture(), eq("module-prove-gen"), anyString());
        String prove = cap.getValue().replaceAll("\\s+", " ");
        assertThat(prove)
                .contains("Study material:")            // the material section marker
                .contains("keep, change, flip")         // the actual brain, injected
                .contains("Base every question and every expectedKeyPoints entry ONLY on the study material");
    }
}
