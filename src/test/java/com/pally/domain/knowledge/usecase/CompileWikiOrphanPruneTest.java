package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.infrastructure.ai.CacheInvalidationService;
import com.pally.infrastructure.ai.CacheKeepAliveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T1: Verifies that orphan wiki pages are archived after compile.
 * If the compile produces only slug-A, then slug-B that exists in the DB
 * must be ARCHIVED.
 */
@ExtendWith(MockitoExtension.class)
class CompileWikiOrphanPruneTest {

    @Mock AvatarRepository avatarRepository;
    @Mock KnowledgeRepository knowledgeRepository;
    @Mock WikiRepository wikiRepository;
    @Mock WikiCompilerPort wikiCompiler;
    @Mock CacheInvalidationService cacheInvalidationService;
    @Mock CacheKeepAliveService cacheKeepAliveService;
    @Mock WikiPagePersistenceService persistenceService;
    @Mock com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository wikiPageSourceRepo;
    @Mock CompileJobStore compileJobStore;

    private CompileWikiUseCase useCase;

    @BeforeEach
    void setUp() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>());

        useCase = new CompileWikiUseCase(
                avatarRepository, knowledgeRepository, wikiRepository,
                wikiCompiler, cacheInvalidationService, cacheKeepAliveService,
                persistenceService, wikiPageSourceRepo, compileJobStore, executor);
        ReflectionTestUtils.setField(useCase, "maxSyncChars", 50000);
    }

    @Test
    void execute_compileProdcesSlugA_slugBGetsArchived() {
        String avatarId = "avatar-prune-test";
        Avatar avatar = Avatar.reconstitute(avatarId, "user-1", "Zap",
                Subject.SCIENCE, CharacterType.MOCHI, 2, Instant.now());

        when(avatarRepository.findById(avatarId)).thenReturn(Optional.of(avatar));

        // Two existing wiki pages: slug-a and slug-b
        WikiPage pageA = WikiPage.reconstitute("id-a", avatarId, "slug-a",
                "Page A", "Content A", WikiPage.Certainty.INFERRED, Instant.now());
        WikiPage pageB = WikiPage.reconstitute("id-b", avatarId, "slug-b",
                "Page B", "Content B", WikiPage.Certainty.INFERRED, Instant.now());

        when(knowledgeRepository.findByAvatarId(avatarId)).thenReturn(List.of(
                makeReadyFile(avatarId, "notes.pdf")));
        when(wikiRepository.countActiveByAvatarId(avatarId)).thenReturn(2);
        when(wikiRepository.findByAvatarId(avatarId)).thenReturn(List.of(pageA, pageB));

        // Compile produces ONLY slug-a
        WikiCompilerPort.WikiPageDraft draftA = new WikiCompilerPort.WikiPageDraft(
                "slug-a", "Page A", "Content A updated", List.of());
        when(wikiCompiler.compileWithTier(any(), any(), any())).thenReturn(
                new WikiCompilerPort.CompileOutput(List.of(draftA), "test-tier"));

        // PersistOutcome returns slug-a as the only produced slug
        when(persistenceService.persistDrafts(any(), any(), any()))
                .thenReturn(new WikiPagePersistenceService.PersistOutcome(
                        0, 1, List.of("Page A"), List.of("slug-a")));

        when(wikiRepository.archiveStalePages(eq(avatarId), any())).thenReturn(0);
        when(wikiRepository.archiveOrphanPages(eq(avatarId), anyList())).thenReturn(1);

        // Execute
        CompileWikiUseCase.CompileResult result = useCase.execute(avatarId);

        // Verify archiveOrphanPages was called with only slug-a as survivor
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> slugsCaptor = ArgumentCaptor.forClass(List.class);
        verify(wikiRepository).archiveOrphanPages(eq(avatarId), slugsCaptor.capture());

        List<String> survivingSlugs = slugsCaptor.getValue();
        assertThat(survivingSlugs)
                .as("Only slug-a was produced; slug-b should be archived")
                .containsExactly("slug-a");
    }

    @Test
    void execute_noReadyFiles_archivesAllActivePages() {
        String avatarId = "avatar-no-files";
        Avatar avatar = Avatar.reconstitute(avatarId, "user-1", "Bolt",
                Subject.MATHS, CharacterType.ZAP, 3, Instant.now());

        when(avatarRepository.findById(avatarId)).thenReturn(Optional.of(avatar));
        when(knowledgeRepository.findByAvatarId(avatarId)).thenReturn(List.of()); // no files
        when(wikiRepository.countActiveByAvatarId(avatarId)).thenReturn(3);
        when(wikiRepository.archiveOrphanPages(eq(avatarId), eq(List.of()))).thenReturn(3);

        CompileWikiUseCase.CompileResult result = useCase.execute(avatarId);

        // archiveOrphanPages must be called with empty list (archives all)
        verify(wikiRepository).archiveOrphanPages(eq(avatarId), eq(List.of()));
        assertThat(result.pagesCreated()).isZero();
        assertThat(result.pagesUpdated()).isZero();
    }

    @Test
    void execute_readyFileWithEmptyExtractedText_skipsCompileAndSpendsNoTokens() {
        String avatarId = "avatar-empty-source";
        Avatar avatar = Avatar.reconstitute(avatarId, "user-1", "Glow",
                Subject.SCIENCE, CharacterType.MOCHI, 1, Instant.now());

        when(avatarRepository.findById(avatarId)).thenReturn(Optional.of(avatar));
        // A READY file whose extracted text is empty — e.g. a scanned/image-only
        // PDF, or a stale record from before the upload empty-text guard existed.
        KnowledgeFile emptyFile = KnowledgeFile.create(avatarId, "user-1",
                "scanned.pdf", "key/scanned.pdf", KnowledgeFile.UploadType.PDF);
        emptyFile.markReady(1); // extractedText stays null → 0 chars
        when(knowledgeRepository.findByAvatarId(avatarId)).thenReturn(List.of(emptyFile));
        when(wikiRepository.countActiveByAvatarId(avatarId)).thenReturn(0);
        when(wikiPageSourceRepo.findCompiledFileIdsByAvatarId(avatarId)).thenReturn(List.of());

        CompileWikiUseCase.CompileResult result = useCase.execute(avatarId);

        // The compiler must NOT be invoked — no Gemini/Haiku tokens spent on a
        // file that can only ever yield 0 pages.
        verify(wikiCompiler, never()).compileWithTier(any(), any(), any());
        assertThat(result.pagesCreated()).isZero();
        assertThat(result.pagesUpdated()).isZero();
        assertThat(result.tierServed()).isEqualTo("skipped-empty-source");
    }

    // ── Bug 1 regression: incremental compile must NOT archive prior files' pages ──

    @Test
    void execute_incrementalCompile_priorFilesPagesAreNotArchived() {
        // File A was compiled first (has source entries). File B is new.
        // Bug: previously archiveOrphanPages only got [b1], archiving [a1, a2].
        // Fix: surviving slugs = union(produced by this run, owned by prior READY files).
        String avatarId = "avatar-incremental-test";
        Avatar avatar = Avatar.reconstitute(avatarId, "user-1", "Spark",
                Subject.SCIENCE, CharacterType.MOCHI, 1, Instant.now());
        when(avatarRepository.findById(avatarId)).thenReturn(Optional.of(avatar));

        KnowledgeFile fileA = makeReadyFile(avatarId, "fileA.pdf");
        KnowledgeFile fileB = makeReadyFile(avatarId, "fileB.pdf");
        when(knowledgeRepository.findByAvatarId(avatarId)).thenReturn(List.of(fileA, fileB));
        when(wikiRepository.countActiveByAvatarId(avatarId)).thenReturn(2);

        // File A is already compiled (has source entries); file B is new
        when(wikiPageSourceRepo.findCompiledFileIdsByAvatarId(avatarId))
                .thenReturn(List.of(fileA.getId()));

        // Source table: file A owns slugs a1 and a2
        when(wikiPageSourceRepo.findActiveSlugsByFileIds(List.of(fileA.getId())))
                .thenReturn(List.of("a1", "a2"));

        WikiPage pageA1 = WikiPage.reconstitute("id-a1", avatarId, "a1", "A1", "content a1",
                WikiPage.Certainty.INFERRED, Instant.now());
        WikiPage pageA2 = WikiPage.reconstitute("id-a2", avatarId, "a2", "A2", "content a2",
                WikiPage.Certainty.INFERRED, Instant.now());
        when(wikiRepository.findByAvatarId(avatarId)).thenReturn(List.of(pageA1, pageA2));

        // This run compiles only file B → produces slug b1
        WikiCompilerPort.WikiPageDraft draftB = new WikiCompilerPort.WikiPageDraft(
                "b1", "B1", "content b1", List.of());
        when(wikiCompiler.compileWithTier(any(), any(), any())).thenReturn(
                new WikiCompilerPort.CompileOutput(List.of(draftB), "test-tier"));
        when(persistenceService.persistDrafts(any(), any(), any()))
                .thenReturn(new WikiPagePersistenceService.PersistOutcome(
                        1, 0, List.of("B1"), List.of("b1")));

        when(wikiRepository.archiveStalePages(eq(avatarId), any())).thenReturn(0);
        when(wikiRepository.archiveOrphanPages(eq(avatarId), anyList())).thenReturn(0);

        useCase.execute(avatarId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> slugsCaptor = ArgumentCaptor.forClass(List.class);
        verify(wikiRepository).archiveOrphanPages(eq(avatarId), slugsCaptor.capture());

        assertThat(slugsCaptor.getValue())
                .as("Surviving slugs must include file A's prior pages AND file B's new page")
                .containsExactlyInAnyOrder("a1", "a2", "b1");
    }

    @Test
    void execute_deletedFile_itsPagesShouldBeArchived() {
        // File A was deleted (no longer READY). File B is READY and compiled.
        // File A's pages must NOT be in the surviving set → they get archived.
        String avatarId = "avatar-deleted-file-test";
        Avatar avatar = Avatar.reconstitute(avatarId, "user-1", "Bolt",
                Subject.MATHS, CharacterType.MOCHI, 1, Instant.now());
        when(avatarRepository.findById(avatarId)).thenReturn(Optional.of(avatar));

        KnowledgeFile fileB = makeReadyFile(avatarId, "fileB.pdf");
        // Only file B is READY (file A was deleted)
        when(knowledgeRepository.findByAvatarId(avatarId)).thenReturn(List.of(fileB));
        when(wikiRepository.countActiveByAvatarId(avatarId)).thenReturn(3);

        // File B is new (not yet compiled)
        when(wikiPageSourceRepo.findCompiledFileIdsByAvatarId(avatarId)).thenReturn(List.of());

        WikiPage pageA1 = WikiPage.reconstitute("id-a1", avatarId, "a1", "A1", "content",
                WikiPage.Certainty.INFERRED, Instant.now());
        when(wikiRepository.findByAvatarId(avatarId)).thenReturn(List.of(pageA1));

        WikiCompilerPort.WikiPageDraft draftB = new WikiCompilerPort.WikiPageDraft(
                "b1", "B1", "content b1", List.of());
        when(wikiCompiler.compileWithTier(any(), any(), any())).thenReturn(
                new WikiCompilerPort.CompileOutput(List.of(draftB), "test-tier"));
        when(persistenceService.persistDrafts(any(), any(), any()))
                .thenReturn(new WikiPagePersistenceService.PersistOutcome(
                        1, 0, List.of("B1"), List.of("b1")));
        when(wikiRepository.archiveStalePages(eq(avatarId), any())).thenReturn(0);
        when(wikiRepository.archiveOrphanPages(eq(avatarId), anyList())).thenReturn(1);

        useCase.execute(avatarId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> slugsCaptor = ArgumentCaptor.forClass(List.class);
        verify(wikiRepository).archiveOrphanPages(eq(avatarId), slugsCaptor.capture());

        assertThat(slugsCaptor.getValue())
                .as("Only file B's page survives; deleted file A's page must not be in surviving set")
                .containsExactly("b1")
                .doesNotContain("a1");
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private KnowledgeFile makeReadyFile(String avatarId, String fileName) {
        KnowledgeFile kf = KnowledgeFile.create(avatarId, "user-1", fileName,
                "key/" + fileName, KnowledgeFile.UploadType.PDF);
        // Non-empty extracted text so the compile path runs (the zero-source
        // guard only short-circuits when every new file has 0 chars).
        kf.setExtractedText("Photosynthesis converts light into chemical energy.");
        kf.markReady(1);
        return kf;
    }
}
