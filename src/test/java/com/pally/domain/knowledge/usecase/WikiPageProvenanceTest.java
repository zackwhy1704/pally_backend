package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.chat.HintTreeGenerator;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.domain.module.ModuleContentGenerator;
import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.ClaudeFlashcardGenerator;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaEntity;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Fix 3 — wiki_page_sources provenance rows are written during compile
 * and replaced on recompile.
 *
 * <p>Zero real Claude API calls — all AI components are mocked.
 */
@ExtendWith(MockitoExtension.class)
class WikiPageProvenanceTest {

    @Mock WikiRepository wikiRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock HintTreeGenerator hintTreeGenerator;
    @Mock ClaudeFlashcardGenerator flashcardGenerator;
    @Mock ClaudeApiClient claudeApiClient;
    @Mock ModelRouter modelRouter;
    @Mock WikiPageSourceJpaRepository wikiPageSourceRepo;
    @Mock ModuleContentGenerator moduleContentGenerator;
    @Mock LearningModuleJpaRepository learningModuleRepository;

    @Captor ArgumentCaptor<List<WikiPageSourceJpaEntity>> savedRowsCaptor;

    private WikiPagePersistenceService service;

    private static final String AVATAR_ID = "avatar-provenance-test";
    private static final String USER_ID = "user-prov";

    private Avatar avatar;
    private KnowledgeFile file1;
    private KnowledgeFile file2;

    @BeforeEach
    void setUp() {
        service = new WikiPagePersistenceService(
                wikiRepository, avatarRepository, hintTreeGenerator,
                flashcardGenerator, claudeApiClient, modelRouter, wikiPageSourceRepo,
                moduleContentGenerator, learningModuleRepository);

        avatar = Avatar.reconstitute(
                AVATAR_ID, USER_ID, "Bolt", Subject.SCIENCE, CharacterType.ZAP,
                0, Instant.now());

        file1 = KnowledgeFile.reconstitute("file-1", AVATAR_ID, USER_ID,
                "chapter1.pdf", "key/chapter1.pdf", 3,
                KnowledgeFile.UploadType.PDF, KnowledgeFile.Status.READY, Instant.now());

        file2 = KnowledgeFile.reconstitute("file-2", AVATAR_ID, USER_ID,
                "notes.jpg", "key/notes.jpg", 1,
                KnowledgeFile.UploadType.PHOTO, KnowledgeFile.Status.READY, Instant.now());

        // Default: no existing page (new page path)
        when(wikiRepository.findByAvatarIdAndSlug(any(), any())).thenReturn(Optional.empty());

        // save returns the page unchanged (enough for the test)
        when(wikiRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // countActiveByAvatarId returns 1 (avatarRepository.save is called for wikiPageCount)
        when(wikiRepository.countActiveByAvatarId(AVATAR_ID)).thenReturn(1);

        when(avatarRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Flashcard + hint tree — best-effort, silently succeed
        doNothing().when(hintTreeGenerator).generateForPage(any(), any());
        doNothing().when(flashcardGenerator).generateAndSaveForPage(any(), any());
    }

    @Test
    void persistDrafts_newPages_writesProvenanceRowsForAllSourceFiles() {
        WikiCompilerPort.WikiPageDraft draft = new WikiCompilerPort.WikiPageDraft(
                "photosynthesis", "Photosynthesis",
                "Plants use sunlight to make food.", List.of());

        service.persistDrafts(avatar, List.of(draft), List.of(file1, file2));

        // Verify deleteByWikiPageId was called (to replace old rows)
        verify(wikiPageSourceRepo, atLeastOnce()).deleteByWikiPageId(any());

        // Verify saveAll was called with 2 rows (one per source file)
        verify(wikiPageSourceRepo).saveAll(savedRowsCaptor.capture());
        List<WikiPageSourceJpaEntity> rows = savedRowsCaptor.getValue();

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(WikiPageSourceJpaEntity::getKnowledgeFileId)
                .containsExactlyInAnyOrder("file-1", "file-2");
    }

    @Test
    void persistDrafts_twoDrafts_writesProvenanceRowsForEachPage() {
        WikiCompilerPort.WikiPageDraft draft1 = new WikiCompilerPort.WikiPageDraft(
                "photosynthesis", "Photosynthesis", "Content A", List.of());
        WikiCompilerPort.WikiPageDraft draft2 = new WikiCompilerPort.WikiPageDraft(
                "osmosis", "Osmosis", "Content B", List.of());

        service.persistDrafts(avatar, List.of(draft1, draft2), List.of(file1));

        // One saveAll call per draft page
        verify(wikiPageSourceRepo, times(2)).saveAll(any());
    }

    @Test
    void persistDrafts_recompileWithFewerFiles_provenanceReplacedNotAppended() {
        // First compile with 2 files
        WikiCompilerPort.WikiPageDraft draft = new WikiCompilerPort.WikiPageDraft(
                "photosynthesis", "Photosynthesis", "Content", List.of());
        service.persistDrafts(avatar, List.of(draft), List.of(file1, file2));

        // Recompile with only file1
        when(wikiRepository.findByAvatarIdAndSlug(any(), any())).thenReturn(Optional.empty());
        service.persistDrafts(avatar, List.of(draft), List.of(file1));

        // deleteByWikiPageId must have been called twice (once per compile)
        verify(wikiPageSourceRepo, times(2)).deleteByWikiPageId(any());
        // saveAll called twice; second call should have only 1 row
        verify(wikiPageSourceRepo, times(2)).saveAll(savedRowsCaptor.capture());
        List<WikiPageSourceJpaEntity> secondCallRows = savedRowsCaptor.getAllValues().get(1);
        assertThat(secondCallRows).hasSize(1);
        assertThat(secondCallRows.get(0).getKnowledgeFileId()).isEqualTo("file-1");
    }

    @Test
    void persistDrafts_emptySourceFiles_doesNotWriteProvenanceRows() {
        WikiCompilerPort.WikiPageDraft draft = new WikiCompilerPort.WikiPageDraft(
                "photosynthesis", "Photosynthesis", "Content", List.of());

        // Old backwards-compat overload — no source files
        service.persistDrafts(avatar, List.of(draft));

        // Provenance repo must not be touched
        verify(wikiPageSourceRepo, never()).deleteByWikiPageId(any());
        verify(wikiPageSourceRepo, never()).saveAll(any());
    }
}
