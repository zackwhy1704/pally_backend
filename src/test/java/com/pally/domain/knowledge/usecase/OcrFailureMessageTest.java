package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.knowledge.ContentDeduplicator;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.OcrPort;
import com.pally.domain.knowledge.port.RelevancePort;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.ai.WikiRecompileScheduler;
import com.pally.infrastructure.ocr.PdfTextExtractor;
import com.pally.infrastructure.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FIX 1 — context-aware OCR failure messages.
 *
 * <p>When extraction yields blank text, the error message should differ between
 * PDF uploads (which may lack selectable text) and image/photo uploads (where
 * lighting / angle are likely culprits).
 */
class OcrFailureMessageTest {

    private UploadFileUseCase useCase;
    private OcrPort ocrPort;
    private PdfTextExtractor pdfTextExtractor;

    @BeforeEach
    void setUp() throws IOException {
        AvatarRepository avatarRepo = mock(AvatarRepository.class);
        KnowledgeRepository knowledgeRepo = mock(KnowledgeRepository.class);
        WikiRepository wikiRepo = mock(WikiRepository.class);
        StorageService storageService = mock(StorageService.class);
        ocrPort = mock(OcrPort.class);
        pdfTextExtractor = mock(PdfTextExtractor.class);
        RelevancePort relevancePort = mock(RelevancePort.class);
        WikiRecompileScheduler recompileScheduler = mock(WikiRecompileScheduler.class);
        ActivityLogService activityLogService = mock(ActivityLogService.class);
        BadgeService badgeService = mock(BadgeService.class);
        PremiumService premiumService = mock(PremiumService.class);
        ConsentGuard consentGuard = mock(ConsentGuard.class);
        ContentDeduplicator deduplicator = mock(ContentDeduplicator.class);
        AvatarSlotGuard avatarSlotGuard = mock(AvatarSlotGuard.class);

        // Stub saves so the use case doesn't NPE
        when(knowledgeRepo.save(any())).thenAnswer(inv -> {
            KnowledgeFile kf = inv.getArgument(0);
            return kf;
        });

        // ConsentGuard + SlotGuard — let everything through
        doNothing().when(consentGuard).requireActive(anyString(), anyString());
        doNothing().when(avatarSlotGuard).requireActive(anyString(), anyString());

        useCase = new UploadFileUseCase(
                avatarRepo, knowledgeRepo, wikiRepo, storageService,
                ocrPort, pdfTextExtractor, relevancePort, recompileScheduler,
                activityLogService, badgeService, premiumService,
                consentGuard, deduplicator, avatarSlotGuard
        );
    }

    @Test
    void blankTextFromPdf_returnsContextualPdfMessage() throws IOException {
        // PDF extraction returns blank text
        when(pdfTextExtractor.extractFromBytes(any()))
                .thenReturn(new PdfTextExtractor.PdfExtractionResult("", 1));

        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.pdf", "application/pdf", "dummy".getBytes());

        UploadResult result = useCase.execute("avatar-1", "user-1", file, true);

        assertThat(result).isInstanceOf(UploadResult.Failure.class);
        UploadResult.Failure failure = (UploadResult.Failure) result;
        assertThat(failure.message())
                .as("PDF-specific message should mention scanned images and text-based PDF")
                .containsIgnoringCase("scanned")
                .containsIgnoringCase("text-based PDF");
        assertThat(failure.message())
                .as("PDF failure message should NOT mention lighting or camera angle")
                .doesNotContainIgnoringCase("lighting")
                .doesNotContainIgnoringCase("angle");
    }

    @Test
    void blankTextFromImage_returnsContextualPhotoMessage() throws IOException {
        // OCR on image returns blank text
        when(ocrPort.extractText(any(), anyString())).thenReturn("");

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", "dummy".getBytes());

        UploadResult result = useCase.execute("avatar-1", "user-1", file, true);

        assertThat(result).isInstanceOf(UploadResult.Failure.class);
        UploadResult.Failure failure = (UploadResult.Failure) result;
        assertThat(failure.message())
                .as("Photo-specific message should mention lighting")
                .containsIgnoringCase("lighting");
        assertThat(failure.message())
                .as("Photo-specific message should mention angle or camera")
                .containsAnyOf("angle", "camera");
        assertThat(failure.message())
                .as("Photo failure message should NOT mention text-based PDF or selectable text")
                .doesNotContainIgnoringCase("selectable text");
    }

    @Test
    void shortTextFromImage_allowedThrough_doesNotFail() throws IOException {
        // Very short text (< 50 chars) from image — should NOT fail, just log a warning
        when(ocrPort.extractText(any(), anyString())).thenReturn("x = 5");
        // Avatar needed for relevance check (skipRelevance=true skips it)
        // Deduplicator — no exception
        doNothing().when(mock(ContentDeduplicator.class)).check(anyString(), anyString(), anyString());

        MockMultipartFile file = new MockMultipartFile(
                "file", "equation.jpg", "image/jpeg", "dummy".getBytes());

        // skipRelevance=true so we don't need avatar or relevance mocks
        UploadResult result = useCase.execute("avatar-1", "user-1", file, true);

        // Short text should NOT produce a Failure — it should be Success or RelevanceWarning
        assertThat(result)
                .as("Short extracted text (< 50 chars) should be allowed through, not failed")
                .isNotInstanceOf(UploadResult.Failure.class);
    }
}
