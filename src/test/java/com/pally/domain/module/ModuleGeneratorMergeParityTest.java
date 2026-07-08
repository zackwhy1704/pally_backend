package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.groundedness.GroundednessVerifier;
import com.pally.domain.module.LearningModule;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.infrastructure.ai.GeminiCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Phase 1 (8→4 a/b generator merge) parity guard. The ONE merged generator must produce
/// the SAME item set — stage, type, content/answer JSON, sort order, count — whether called
/// the student way (guidanceSection="") or the teacher-regenerate way (guidanceSection set).
/// The guidance only changes the PROMPT, never the item structure. And the merged generator
/// yields LIVE items (status defaults LIVE); the DRAFT marker is applied by the regenerate
/// path, not the generator — so the student path never accidentally emits DRAFT.
@ExtendWith(MockitoExtension.class)
class ModuleGeneratorMergeParityTest {

    @Mock GeminiCompletionService gemini;
    @Mock PremiumService premiumService;
    @Mock GroundednessVerifier groundednessVerifier;
    @Mock ModuleWriter moduleWriter;
    ModuleContentGenerator gen;

    private static final String GUIDANCE = "\n\nTeacher guidance to incorporate:\nfocus on the core idea\n";

    @BeforeEach
    void setUp() {
        gen = new ModuleContentGenerator(gemini, new ObjectMapper(),
                premiumService, groundednessVerifier, moduleWriter);
    }

    private void assertSameStructure(List<ModuleContentItem> a, List<ModuleContentItem> b) {
        assertThat(a).hasSameSizeAs(b);
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i).getStage()).isEqualTo(b.get(i).getStage());
            assertThat(a.get(i).getType()).isEqualTo(b.get(i).getType());
            assertThat(a.get(i).getSortOrder()).isEqualTo(b.get(i).getSortOrder());
            assertThat(a.get(i).getContentJson()).isEqualTo(b.get(i).getContentJson());
            assertThat(a.get(i).getAnswerJson()).isEqualTo(b.get(i).getAnswerJson());
            assertThat(a.get(i).getTierRequired()).isEqualTo(b.get(i).getTierRequired());
        }
    }

    @Test
    void microCards_studentVsTeacherGuidance_identicalItemSet_andLiveStatus() {
        when(gemini.complete(anyInt(), any(), any())).thenReturn(
                "[{\"title\":\"Photosynthesis\",\"body\":\"light to chemical energy\",\"keyTerms\":[\"light\"]}]");
        List<ModuleContentItem> student = gen.generateMicroCards("m1", "content", "P5", "Science", "FREE", "", "av-test");
        List<ModuleContentItem> teacher = gen.generateMicroCards("m1", "content", "P5", "Science", "FREE", GUIDANCE, "av-test");
        assertSameStructure(student, teacher);
        assertThat(student.get(0).getStage()).isEqualTo("LEARN");
        assertThat(student.get(0).getType()).isEqualTo("MICRO_CARD");
        // Merged generator yields LIVE; DRAFT is applied by the regenerate path, not here.
        assertThat(student.get(0).getStatus()).isEqualTo("LIVE");
    }

    @Test
    void spotMistake_studentVsTeacherGuidance_identicalItemSet() {
        when(gemini.complete(anyInt(), any(), any())).thenReturn(
                "{\"problem\":\"2+2=5\",\"wrongSolution\":\"added wrong\","
                        + "\"errorDescription\":\"arithmetic slip\",\"correctSolution\":\"2+2=4\"}");
        List<ModuleContentItem> student = gen.generateSpotMistake("m1", "content", "P5", "Science", "", "av-test");
        List<ModuleContentItem> teacher = gen.generateSpotMistake("m1", "content", "P5", "Science", GUIDANCE, "av-test");
        assertSameStructure(student, teacher);
        assertThat(student.get(0).getType()).isEqualTo("SPOT_MISTAKE");
        assertThat(student.get(0).getSortOrder()).isEqualTo(200);
    }

    @Test
    void challenges_studentVsTeacherGuidance_identicalItemSet() {
        when(gemini.complete(anyInt(), any(), any())).thenReturn(
                "[{\"question\":\"Explain X\",\"answer\":\"because Y\",\"explanation\":\"Z\",\"difficulty\":\"easy\"}]");
        List<ModuleContentItem> student = gen.generateChallenges("m1", "content", "P5", "Science", "FREE", "", "av-test");
        List<ModuleContentItem> teacher = gen.generateChallenges("m1", "content", "P5", "Science", "FREE", GUIDANCE, "av-test");
        assertSameStructure(student, teacher);
        assertThat(student.get(0).getType()).isEqualTo("CHALLENGE");
        assertThat(student.get(0).getSortOrder()).isEqualTo(300);
    }

    /// The invariant this merge most endangered: teacher-regenerate previews must be marked
    /// DRAFT, or they leak into LIVE student content. The merged generators are DRAFT-neutral
    /// (LIVE); regenerateAsDraft MUST apply DRAFT. Nothing else tests this — CentreRegenerate-
    /// ServiceTest mocks the generator away — so it lives here, next to the student→LIVE side.
    @Test
    void regenerateAsDraft_marksEveryItemDraft_soPreviewsNeverLeakLive() {
        when(gemini.complete(anyInt(), any(), any())).thenReturn(
                "[{\"title\":\"Fractions\",\"body\":\"parts of a whole\",\"keyTerms\":[]}]");
        lenient().when(premiumService.resolveTier(any())).thenReturn(SubscriptionTier.FREE);

        Avatar avatar = Avatar.reconstitute("cav", "teacher-1", "P4 Maths",
                Subject.MATHS, CharacterType.MOCHI, 5, Instant.now());
        WikiPage page = WikiPage.reconstitute("p1", "cav", "fractions", "Fractions",
                "Fractions are parts of a whole.", WikiPage.Certainty.INFERRED, Instant.now());
        LearningModule module = new LearningModule();
        module.setId("mod1");

        gen.regenerateAsDraft(avatar, page, module, "focus on word problems");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ModuleContentItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(moduleWriter).replaceItems(eq("mod1"), captor.capture());
        List<ModuleContentItem> persisted = captor.getValue();
        assertThat(persisted).isNotEmpty();
        assertThat(persisted).allSatisfy(item ->
                assertThat(item.getStatus())
                        .as("every regenerated item must be DRAFT (else it leaks into live content)")
                        .isEqualTo("DRAFT"));
    }
}
