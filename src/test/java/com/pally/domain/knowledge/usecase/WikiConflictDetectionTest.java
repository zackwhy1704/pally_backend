package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.chat.HintTreeGenerator;
import com.pally.domain.knowledge.WikiQualityVerifier;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.module.ModuleContentGenerator;
import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.ClaudeFlashcardGenerator;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conflict detection must catch the contradictions that matter most: small, buried
 * factual flips in otherwise-similar prose (e.g. "38 ATP" vs "36 ATP"). The token-set
 * Jaccard scores those ~0.83 → high-overlap → previously auto-passed as NONE, so the
 * brain absorbed contradictions invisibly. A deterministic fact diff fixes that on the
 * high-overlap band, without false-positiving on paraphrases/supersets.
 */
@ExtendWith(MockitoExtension.class)
class WikiConflictDetectionTest {

    @Mock WikiRepository wikiRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock HintTreeGenerator hintTreeGenerator;
    @Mock ClaudeFlashcardGenerator flashcardGenerator;
    @Mock ClaudeApiClient claudeApiClient;
    @Mock ModelRouter modelRouter;
    @Mock WikiPageSourceJpaRepository wikiPageSourceRepo;
    @Mock ModuleContentGenerator moduleContentGenerator;
    @Mock LearningModuleJpaRepository learningModuleRepository;
    @Mock ObjectProvider<WikiPagePersistenceService> selfProvider;

    private WikiPagePersistenceService service;

    @BeforeEach
    void setUp() {
        service = new WikiPagePersistenceService(
                wikiRepository, avatarRepository, hintTreeGenerator, flashcardGenerator,
                claudeApiClient, modelRouter, wikiPageSourceRepo,
                moduleContentGenerator, learningModuleRepository, new WikiQualityVerifier(),
                selfProvider,
                org.mockito.Mockito.mock(com.pally.domain.knowledge.WikiConflictService.class));
    }

    // ── STEP 1 positives: the deterministic fact diff (no LLM) ──────────────────

    @Test
    void numericFlip_isFlagged_withConcreteNote() {
        String note = WikiPagePersistenceService.detectFactConflict(
                "Aerobic respiration produces 38 ATP per glucose molecule.",
                "Aerobic respiration produces 36 ATP per glucose molecule.");
        assertThat(note).isNotNull();
        assertThat(note).contains("38").contains("36");
    }

    @Test
    void unitFlip_isFlagged() {
        String note = WikiPagePersistenceService.detectFactConflict(
                "Water boils at 100 C at sea level.",
                "Water boils at 90 C at sea level.");
        assertThat(note).contains("100").contains("90");
    }

    @Test
    void dateFlip_isFlagged() {
        String note = WikiPagePersistenceService.detectFactConflict(
                "The treaty was signed in 1945 after the war.",
                "The treaty was signed in 1944 after the war.");
        assertThat(note).contains("1945").contains("1944");
    }

    @Test
    void numberBuriedInIdenticalParagraph_isFlagged_provingSimilarityScalingFailure() {
        String base = "In the experiment the students measured the temperature carefully and "
                + "recorded that the metal sample reached exactly %s degrees before it began "
                + "to melt slowly under the bright laboratory lights during the long afternoon.";
        String note = WikiPagePersistenceService.detectFactConflict(
                base.formatted("250"), base.formatted("200"));
        assertThat(note).contains("250").contains("200");
    }

    @Test
    void namedEntityFlip_isFlagged() {
        String note = WikiPagePersistenceService.detectFactConflict(
                "The capital city is Canberra in the south east.",
                "The capital city is Sydney in the south east.");
        assertThat(note).contains("Canberra").contains("Sydney");
    }

    // ── STEP 1 negatives: must stay null (no false positives) ───────────────────

    @Test
    void paraphrase_sameNumber_isNotFlagged() {
        // Same fact, different wording — the number is identical, the context differs.
        String note = WikiPagePersistenceService.detectFactConflict(
                "In aerobic respiration the mitochondria produces 38 ATP for the cell.",
                "In aerobic respiration the mitochondria yields 38 molecules of ATP for the cell.");
        assertThat(note).isNull();
    }

    @Test
    void superset_addedDetailNoClash_isNotFlagged() {
        String note = WikiPagePersistenceService.detectFactConflict(
                "Water boils at 100 degrees and turns into steam.",
                "Water boils at 100 degrees and turns into steam under normal atmospheric pressure.");
        assertThat(note).isNull();
    }

    // ── STEP 3 band wiring: detectConflict now flags on the HIGH-OVERLAP band ────
    // (These pairs return NONE on the pre-fix code — the documented gap.)

    @Test
    void detectConflict_atpFlipInSimilarProse_nowFlagsWithNote_notNONE() {
        String a = "The mitochondria is the powerhouse of the cell and produces 38 ATP "
                + "per glucose molecule during aerobic respiration.";
        String b = "The mitochondria is the powerhouse of the cell and produces 36 ATP "
                + "per glucose molecule during aerobic respiration.";

        WikiPagePersistenceService.ConflictResult result = service.detectConflict(a, b);

        assertThat(result.conflict()).isTrue();
        assertThat(result.note()).contains("38").contains("36");
        // Deterministic path — the Haiku judge is never consulted for the numeric flip.
        org.mockito.Mockito.verifyNoInteractions(claudeApiClient);
    }

    @Test
    void detectConflict_grayBandNumericContradiction_flagsDeterministically_withoutHaiku() {
        // Two rephrased pages on the same topic, Jaccard ~0.50 (the GRAY band), that
        // contradict only on the number. Pre-fix this fell to haikuContradicts, which
        // rationalized "36 vs 38 ATP" away as "different sources" and returned NO.
        // B1: the deterministic check runs first on every collision and decides.
        String a = "The mitochondria produces 38 ATP per glucose molecule during aerobic "
                 + "respiration. It is the powerhouse of the cell and has a double membrane "
                 + "with folded cristae.";
        String b = "The mitochondria produces 36 ATP per glucose molecule during aerobic "
                 + "respiration. It is the powerhouse of the cell and contains its own "
                 + "circular DNA inside the matrix.";

        WikiPagePersistenceService.ConflictResult result = service.detectConflict(a, b);

        assertThat(result.conflict()).isTrue();
        assertThat(result.note()).contains("38").contains("36");
        org.mockito.Mockito.verifyNoInteractions(claudeApiClient); // deterministic, never asked the LLM
    }

    @Test
    void detectConflict_grayBandParaphrase_sameNumber_staysNONE() {
        // Same number, only a verb paraphrase + different second sentence (gray band).
        // The deterministic check must NOT false-positive (different context, same value);
        // it falls through to Haiku for the prose, which finds no contradiction.
        String a = "The mitochondria produces 38 ATP per glucose molecule during aerobic "
                 + "respiration. It is the powerhouse of the cell and has a double membrane.";
        String b = "The mitochondria generates 38 ATP per glucose molecule during aerobic "
                 + "respiration. It is the powerhouse of the cell and contains its own DNA.";

        assertThat(WikiPagePersistenceService.detectFactConflict(a, b)).isNull(); // no false fact-clash
        assertThat(service.detectConflict(a, b).conflict()).isFalse();
    }

    @Test
    void detectConflict_highOverlapParaphrase_staysNONE() {
        String a = "In aerobic respiration the mitochondria produces 38 ATP for the cell to use as energy.";
        String b = "In aerobic respiration the mitochondria yields 38 molecules of ATP for the cell to use as energy.";

        WikiPagePersistenceService.ConflictResult result = service.detectConflict(a, b);

        assertThat(result.conflict()).isFalse();
    }
}
