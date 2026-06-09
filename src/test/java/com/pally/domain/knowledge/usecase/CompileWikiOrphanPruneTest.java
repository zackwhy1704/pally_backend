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

    private CompileWikiUseCase useCase;

    @BeforeEach
    void setUp() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>());

        useCase = new CompileWikiUseCase(
                avatarRepository, knowledgeRepository, wikiRepository,
                wikiCompiler, cacheInvalidationService, cacheKeepAliveService,
                persistenceService, wikiPageSourceRepo, executor);
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

    // ── Helper ───────────────────────────────────────────────────────────────

    private KnowledgeFile makeReadyFile(String avatarId, String fileName) {
        KnowledgeFile kf = KnowledgeFile.create(avatarId, "user-1", fileName,
                "key/" + fileName, KnowledgeFile.UploadType.PDF);
        kf.markReady(1);
        return kf;
    }
}
