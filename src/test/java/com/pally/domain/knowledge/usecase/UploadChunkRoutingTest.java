package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.knowledge.ContentDeduplicator;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.OcrQualityGate;
import com.pally.domain.knowledge.Segment;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.OcrPort;
import com.pally.domain.knowledge.port.RelevancePort;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.UploadQuotaGuard;
import com.pally.infrastructure.ai.WikiRecompileScheduler;
import com.pally.infrastructure.ocr.PdfTextExtractor;
import com.pally.infrastructure.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Upload ROUTING invariants for chapter-chunking:
 *  - a ≤trigger upload is byte-identical to pre-chunking (Success, recompile fired,
 *    segmentation never consulted) — the worksheet/photo majority must not regress;
 *  - a >trigger valid upload is SEGMENTED into PENDING_CHUNK children with NO eager
 *    compile (recompile never scheduled) — the money rule;
 *  - a >trigger upload that yields <2 segments falls back to a whole compile.
 */
class UploadChunkRoutingTest {

    private UploadFileUseCase useCase;
    private KnowledgeRepository knowledgeRepo;
    private WikiRecompileScheduler recompileScheduler;
    private DocumentSegmentationService segmentationService;

    @BeforeEach
    void setUp() {
        AvatarRepository avatarRepo = mock(AvatarRepository.class);
        knowledgeRepo = mock(KnowledgeRepository.class);
        WikiRepository wikiRepo = mock(WikiRepository.class);
        StorageService storageService = mock(StorageService.class);
        OcrPort ocrPort = mock(OcrPort.class);
        PdfTextExtractor pdf = mock(PdfTextExtractor.class);
        RelevancePort relevancePort = mock(RelevancePort.class);
        recompileScheduler = mock(WikiRecompileScheduler.class);
        ActivityLogService activityLogService = mock(ActivityLogService.class);
        BadgeService badgeService = mock(BadgeService.class);
        PremiumService premiumService = mock(PremiumService.class);
        ConsentGuard consentGuard = mock(ConsentGuard.class);
        ContentDeduplicator deduplicator = mock(ContentDeduplicator.class);
        AvatarSlotGuard avatarSlotGuard = mock(AvatarSlotGuard.class);
        UploadQuotaGuard uploadQuotaGuard = mock(UploadQuotaGuard.class);
        segmentationService = mock(DocumentSegmentationService.class);

        when(knowledgeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(deduplicator.computeHash(anyString())).thenReturn("hash");

        useCase = new UploadFileUseCase(
                avatarRepo, knowledgeRepo, wikiRepo, storageService,
                ocrPort, pdf, relevancePort, recompileScheduler,
                activityLogService, badgeService, premiumService,
                consentGuard, deduplicator, avatarSlotGuard, new OcrQualityGate(),
                uploadQuotaGuard, segmentationService);
        // @Value fields aren't injected without Spring — set the production defaults.
        org.springframework.test.util.ReflectionTestUtils.setField(useCase, "segmentTriggerChars", 50_000);
        org.springframework.test.util.ReflectionTestUtils.setField(useCase, "uploadRejectChars", 5_000_000);
    }

    private MockMultipartFile textFile(int chars) {
        return new MockMultipartFile("file", "notes.txt", "text/plain", "a".repeat(chars).getBytes());
    }

    private List<KnowledgeFile> savedFiles() {
        ArgumentCaptor<KnowledgeFile> cap = ArgumentCaptor.forClass(KnowledgeFile.class);
        verify(knowledgeRepo, atLeastOnce()).save(cap.capture());
        return cap.getAllValues();
    }

    @Test
    void smallUpload_isByteIdentical_success_recompileFired_noSegmentation() {
        UploadResult result = useCase.execute("av1", "u1", textFile(10_000), true);

        assertThat(result).isInstanceOf(UploadResult.Success.class);
        verify(recompileScheduler).requestRecompile("av1");          // compiles as today
        verify(segmentationService, never()).segment(any(), any(), anyString(), anyInt(), any());
        // the single READY file, no chunks
        assertThat(savedFiles()).allSatisfy(f -> assertThat(f.isChunk()).isFalse());
    }

    @Test
    void largeUpload_isSegmented_intoPendingChunks_withNoEagerCompile() {
        when(segmentationService.segment(any(), eq(KnowledgeFile.UploadType.TEXT), anyString(), anyInt(), any()))
                .thenReturn(List.of(
                        new Segment("Chapter 1", 1, 25, "chapter one slice"),
                        new Segment("Chapter 2", 26, 50, "chapter two slice")));

        UploadResult result = useCase.execute("av1", "u1", textFile(60_000), true);

        assertThat(result).isInstanceOf(UploadResult.Segmented.class);
        UploadResult.Segmented seg = (UploadResult.Segmented) result;
        assertThat(seg.chunks()).hasSize(2);
        assertThat(seg.chunks()).extracting(UploadResult.ChunkInfo::title)
                .containsExactly("Chapter 1", "Chapter 2");

        // MONEY RULE: nothing compiles — no recompile is scheduled for an unpicked chunk.
        verify(recompileScheduler, never()).requestRecompile(any());

        List<KnowledgeFile> saved = savedFiles();
        // parent persisted SEGMENTED (compile-ignored)
        assertThat(saved).anySatisfy(f ->
                assertThat(f.getStatus()).isEqualTo(KnowledgeFile.Status.SEGMENTED));
        // two children, each PENDING_CHUNK + compiled_by null + distinct slice
        List<KnowledgeFile> children = saved.stream().filter(KnowledgeFile::isChunk).toList();
        assertThat(children).hasSize(2);
        assertThat(children).allSatisfy(c -> {
            assertThat(c.getStatus()).isEqualTo(KnowledgeFile.Status.PENDING_CHUNK);
            assertThat(c.getCompiledBy()).isNull();
        });
        assertThat(children.get(0).getExtractedText()).isNotEqualTo(children.get(1).getExtractedText());
    }

    @Test
    void largeUpload_butUnderTwoSegments_fallsBackToWholeCompile() {
        when(segmentationService.segment(any(), any(), anyString(), anyInt(), any()))
                .thenReturn(List.of(new Segment("All", 1, 30, "one big slice")));

        UploadResult result = useCase.execute("av1", "u1", textFile(60_000), true);

        assertThat(result).isInstanceOf(UploadResult.Success.class);
        verify(recompileScheduler).requestRecompile("av1"); // compiles whole, as a normal upload
    }
}
