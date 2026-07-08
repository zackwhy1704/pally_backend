package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.infrastructure.ai.CacheInvalidationService;
import com.pally.infrastructure.ai.CacheKeepAliveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Item 1 — a segmented file whose compile FAILS mid-run must be left INCOMPLETE
 * (compiled_by NULL) so the next run re-compiles it, rather than being silently
 * marked done and its un-compiled segments lost forever.
 */
@ExtendWith(MockitoExtension.class)
class CompileResumeTest {

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
                persistenceService, wikiPageSourceRepo, compileJobStore,
                org.mockito.Mockito.mock(DocumentSegmentationService.class), executor);
        // Small window so a 5000-char file segments into several batches.
        ReflectionTestUtils.setField(useCase, "maxSyncChars", 2000);
    }

    private final Avatar avatar = Avatar.reconstitute("av-1", "u-1", "Mochi",
            Subject.SCIENCE, CharacterType.MOCHI, 2, Instant.now());

    private KnowledgeFile bigFile() {
        KnowledgeFile f = KnowledgeFile.create("av-1", "u-1", "book.pdf",
                "key/book.pdf", KnowledgeFile.UploadType.PDF);
        f.setExtractedText("x".repeat(5000)); // > maxSyncChars → multiple segments
        f.markReady(50);
        return f; // compiledBy defaults to null
    }

    private WikiCompilerPort.CompileOutput okOutput() {
        return new WikiCompilerPort.CompileOutput(
                List.of(new WikiCompilerPort.WikiPageDraft("slug-a", "A", "content", List.of())),
                "test-tier");
    }

    @Test
    void midRunSegmentFailure_leavesFileIncomplete_notMarkedCompiled() {
        when(avatarRepository.findById("av-1")).thenReturn(Optional.of(avatar));
        when(knowledgeRepository.findByAvatarId("av-1")).thenReturn(List.of(bigFile()));
        when(wikiRepository.findByAvatarId("av-1")).thenReturn(List.of());
        // First segment compiles; the second throws; the rest is irrelevant.
        when(wikiCompiler.compileWithTier(any(), any(), any()))
                .thenReturn(okOutput())
                .thenThrow(new RuntimeException("boom mid-file"))
                .thenReturn(okOutput());
        lenient().when(persistenceService.persistDrafts(any(), any(), any()))
                .thenReturn(new WikiPagePersistenceService.PersistOutcome(
                        1, 0, List.of("A"), List.of("slug-a")));

        useCase.executeBatched("av-1", null);

        // The file had a failed segment → compiled_by must NOT be set, so the
        // only place executeBatched saves it (the completion marker) never runs.
        verify(knowledgeRepository, never()).save(any());
    }

    @Test
    void zeroPageCompile_fileNotMarkedCompiled_staysRecompilable() {
        // The soft-empty bug: the compile does NOT throw but produces 0 pages (all
        // AI tiers degraded to empty). The file must NOT be marked compiled — else
        // it's skipped forever with an empty wiki (silent data loss).
        when(avatarRepository.findById("av-1")).thenReturn(Optional.of(avatar));
        when(knowledgeRepository.findByAvatarId("av-1")).thenReturn(List.of(bigFile()));
        when(wikiRepository.findByAvatarId("av-1")).thenReturn(List.of());
        when(wikiCompiler.compileWithTier(any(), any(), any())).thenReturn(okOutput());
        // persistDrafts reports ZERO created + ZERO updated for every batch.
        when(persistenceService.persistDrafts(any(), any(), any()))
                .thenReturn(new WikiPagePersistenceService.PersistOutcome(0, 0, List.of(), List.of()));

        useCase.executeBatched("av-1", null);

        // compiled_by is never set → the completion-marker save never runs.
        verify(knowledgeRepository, never()).save(any());
    }

    @Test
    void allSegmentsSucceed_fileMarkedCompiled() {
        when(avatarRepository.findById("av-1")).thenReturn(Optional.of(avatar));
        when(knowledgeRepository.findByAvatarId("av-1")).thenReturn(List.of(bigFile()));
        when(wikiRepository.findByAvatarId("av-1")).thenReturn(List.of());
        when(wikiCompiler.compileWithTier(any(), any(), any())).thenReturn(okOutput());
        when(persistenceService.persistDrafts(any(), any(), any()))
                .thenReturn(new WikiPagePersistenceService.PersistOutcome(
                        1, 0, List.of("A"), List.of("slug-a")));

        useCase.executeBatched("av-1", null);

        // Every segment succeeded → the original file is marked compiled exactly once.
        verify(knowledgeRepository, times(1)).save(argThat(f ->
                f.getId() != null && f.getCompiledBy() != null));
    }
}
