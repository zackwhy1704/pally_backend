package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.Segment;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The legacy-file sweep guard — the RETROACTIVE half of the money-leak fix. A
 * pre-existing oversized READY file (compiled_by null, no children) must be
 * segmented into PENDING_CHUNK children instead of being compiled WHOLE. Without
 * this, a legacy textbook would eagerly compile (~$2+) on the next debounce tick
 * before anyone ever saw a picker.
 */
@ExtendWith(MockitoExtension.class)
class CompileWikiLegacySweepTest {

    @Mock AvatarRepository avatarRepository;
    @Mock KnowledgeRepository knowledgeRepository;
    @Mock WikiRepository wikiRepository;
    @Mock WikiCompilerPort wikiCompiler;
    @Mock CacheInvalidationService cacheInvalidationService;
    @Mock CacheKeepAliveService cacheKeepAliveService;
    @Mock WikiPagePersistenceService persistenceService;
    @Mock com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository wikiPageSourceRepo;
    @Mock CompileJobStore compileJobStore;
    @Mock DocumentSegmentationService segmentationService;

    private CompileWikiUseCase useCase;

    @BeforeEach
    void setUp() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>());
        useCase = new CompileWikiUseCase(
                avatarRepository, knowledgeRepository, wikiRepository,
                wikiCompiler, cacheInvalidationService, cacheKeepAliveService,
                persistenceService, wikiPageSourceRepo, compileJobStore,
                segmentationService, executor);
        ReflectionTestUtils.setField(useCase, "maxSyncChars", 50_000);
        ReflectionTestUtils.setField(useCase, "segmentTriggerChars", 50_000);
        lenient().when(avatarRepository.findById("av")).thenReturn(Optional.of(
                Avatar.reconstitute("av", "u", "Mochi", Subject.SCIENCE, CharacterType.MOCHI, 1, Instant.now())));
        lenient().when(knowledgeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private KnowledgeFile legacyOversized() {
        KnowledgeFile f = KnowledgeFile.create("av", "u", "textbook.pdf", "k/t.pdf",
                KnowledgeFile.UploadType.PDF);
        f.markReady(300);
        f.setExtractedText("x".repeat(120_000)); // > 50k trigger, compiled_by null, no children
        return f;
    }

    @Test
    void oversizedLegacyFile_isSegmented_notCompiledWhole() {
        KnowledgeFile legacy = legacyOversized();
        when(knowledgeRepository.findByAvatarId("av")).thenReturn(List.of(legacy));
        when(knowledgeRepository.hasChunks(legacy.getId())).thenReturn(false);
        when(segmentationService.segmentFromText(anyString())).thenReturn(List.of(
                new Segment("Pages 1–25", 1, 25, "chunk a"),
                new Segment("Pages 26–50", 26, 50, "chunk b")));

        CompileWikiUseCase.CompileResult result = useCase.executeBatched("av", "job-1");

        // Nothing compiled whole — the AI compiler was never invoked for the legacy file.
        verify(wikiCompiler, never()).compile(any(), any(), any());

        ArgumentCaptor<KnowledgeFile> saved = ArgumentCaptor.forClass(KnowledgeFile.class);
        verify(knowledgeRepository, atLeastOnce()).save(saved.capture());
        // parent moved to SEGMENTED, and two PENDING_CHUNK children were persisted
        assertThat(saved.getAllValues()).anySatisfy(f ->
                assertThat(f.getStatus()).isEqualTo(KnowledgeFile.Status.SEGMENTED));
        List<KnowledgeFile> children = saved.getAllValues().stream().filter(KnowledgeFile::isChunk).toList();
        assertThat(children).hasSize(2);
        assertThat(children).allSatisfy(c ->
                assertThat(c.getStatus()).isEqualTo(KnowledgeFile.Status.PENDING_CHUNK));
        assertThat(result.pagesCreated()).isZero();
    }

    @Test
    void fileThatAlreadyHasChunks_isNotReSegmented() {
        KnowledgeFile legacy = legacyOversized();
        when(knowledgeRepository.findByAvatarId("av")).thenReturn(List.of(legacy));
        when(knowledgeRepository.hasChunks(legacy.getId())).thenReturn(true); // already split

        useCase.executeBatched("av", "job-1");

        verify(segmentationService, never()).segmentFromText(anyString());
    }
}
