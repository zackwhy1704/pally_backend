package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.chat.HintTreeGenerator;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiQualityVerifier;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.domain.module.ModuleContentGenerator;
import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.ClaudeFlashcardGenerator;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * IMPROVEMENT 3 — surface conflict detail.
 *
 * <p>When the gray-band Haiku verdict says "YES — &lt;reason&gt;", the persisted
 * page must carry both hasConflict=true and the extracted reason text in
 * conflictNote (capped at 500 chars, separator stripped). A "NO" verdict leaves
 * conflictNote null.
 */
@ExtendWith(MockitoExtension.class)
class WikiPageConflictNoteTest {

    @Mock WikiRepository wikiRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock HintTreeGenerator hintTreeGenerator;
    @Mock ClaudeFlashcardGenerator flashcardGenerator;
    @Mock ClaudeApiClient claudeApiClient;
    @Mock ModelRouter modelRouter;
    @Mock WikiPageSourceJpaRepository wikiPageSourceRepo;
    @Mock ModuleContentGenerator moduleContentGenerator;
    @Mock LearningModuleJpaRepository learningModuleRepository;

    private WikiPagePersistenceService service;

    private static final String AVATAR_ID = "avatar-conflict-test";
    private static final String SLUG = "boiling-point";

    @BeforeEach
    void setUp() {
        WikiQualityVerifier wikiQualityVerifier = new WikiQualityVerifier();
        org.springframework.beans.factory.ObjectProvider<WikiPagePersistenceService> selfProvider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        service = new WikiPagePersistenceService(
                wikiRepository, avatarRepository, hintTreeGenerator, flashcardGenerator,
                claudeApiClient, modelRouter, wikiPageSourceRepo,
                moduleContentGenerator, learningModuleRepository, wikiQualityVerifier,
                selfProvider,
                org.mockito.Mockito.mock(com.pally.domain.knowledge.WikiConflictService.class));
        org.mockito.Mockito.lenient().when(selfProvider.getObject()).thenReturn(service);
    }

    // ── extractConflictNote() — pure string parsing ──────────────────────────

    @Test
    void extractConflictNote_yesWithEmDashReason_returnsTrimmedReason() {
        assertThat(WikiPagePersistenceService.extractConflictNote(
                "YES — contradicts the earlier definition of X"))
                .isEqualTo("contradicts the earlier definition of X");
    }

    @Test
    void extractConflictNote_yesWithColon_stripsColonSeparator() {
        assertThat(WikiPagePersistenceService.extractConflictNote(
                "YES: different boiling point value"))
                .isEqualTo("different boiling point value");
    }

    @Test
    void extractConflictNote_yesNoReason_returnsNull() {
        assertThat(WikiPagePersistenceService.extractConflictNote("YES")).isNull();
    }

    @Test
    void extractConflictNote_isNoLongerCapped_sinceColumnIsText() {
        // conflict_note is now TEXT (V93) — the old raw substring(0,500) cap could
        // split a UTF-16 surrogate pair at the boundary and fail the DB write with
        // an encoding error. Free-text reasons are no longer truncated.
        String longReason = "x".repeat(600);
        String note = WikiPagePersistenceService.extractConflictNote("YES - " + longReason);
        assertThat(note).hasSize(600);
    }

    // ── full persist path drives the gray-band Haiku verdict ─────────────────

    @Test
    void persistDrafts_haikuYesVerdict_storesConflictNoteAndFlag() {
        // Existing + incoming contents share enough words to land in the gray
        // band (Jaccard between 0.40 and 0.70) so the Haiku check fires.
        // Jaccard ≈ 0.64 — in the [0.40, 0.70) gray band so the Haiku check fires.
        String existing = "Water boils at one hundred degrees celsius at sea level";
        String incoming = "Water boils at ninety degrees celsius at high sea level";
        WikiPage existingPage = makePage("page-1", SLUG, existing);

        Avatar avatar = Avatar.reconstitute(
                AVATAR_ID, "user-1", "Zap", Subject.SCIENCE, CharacterType.ZAP,
                0, Instant.now());

        when(wikiRepository.findByAvatarIdAndSlug(AVATAR_ID, SLUG))
                .thenReturn(Optional.of(existingPage));
        lenient().when(wikiRepository.findActiveByAvatarId(AVATAR_ID)).thenReturn(List.of());
        lenient().when(wikiRepository.countActiveByAvatarId(AVATAR_ID)).thenReturn(1);
        lenient().when(learningModuleRepository.findByAvatarIdAndWikiPageSlug(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(modelRouter.forRelevanceCheck()).thenReturn("haiku");
        when(claudeApiClient.complete(eq("haiku"), anyInt(), anyString()))
                .thenReturn("YES — contradicts the earlier definition of X");
        // save() echoes the page back so producedSlugs re-fetch works.
        when(wikiRepository.save(any(WikiPage.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WikiCompilerPort.WikiPageDraft draft =
                new WikiCompilerPort.WikiPageDraft(SLUG, "Boiling Point", incoming);

        service.persistDrafts(avatar, List.of(draft), List.of());

        ArgumentCaptor<WikiPage> captor = ArgumentCaptor.forClass(WikiPage.class);
        org.mockito.Mockito.verify(wikiRepository, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        WikiPage saved = captor.getAllValues().stream()
                .filter(p -> SLUG.equals(p.getSlug()))
                .findFirst().orElseThrow();

        assertThat(saved.isHasConflict())
                .as("Haiku YES verdict flags the page as conflicting")
                .isTrue();
        assertThat(saved.getConflictNote())
                .as("Extracted reason is stored on the page")
                .isEqualTo("contradicts the earlier definition of X");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private WikiPage makePage(String id, String slug, String content) {
        return WikiPage.reconstitute(
                id, AVATAR_ID, slug, slug, content,
                WikiPage.Certainty.INFERRED, Instant.now(),
                0, null, null, false,
                null, 0, 0.5, WikiPage.Status.ACTIVE, false, null);
    }
}
