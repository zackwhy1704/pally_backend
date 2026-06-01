package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.RelevanceScore;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.RelevancePort;
import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.knowledge.ContentDeduplicator;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.infrastructure.ocr.PdfTextExtractor;
import com.pally.domain.knowledge.port.OcrPort;
import com.pally.infrastructure.storage.StorageService;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.util.TextSampler;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Upload a file, run OCR/extraction + relevance check synchronously, then
 * kick off wiki compilation in a background thread so the HTTP response
 * returns within seconds — well under Railway's 60s proxy timeout.
 *
 * <p>Wiki compilation is the slow step (30–120s per document). Moving it
 * off-thread makes the upload feel instant: the file is accepted, the brain
 * starts building, and the frontend navigates to the success screen
 * immediately. The wiki viewer will populate as pages are written.
 */
@Service
public class UploadFileUseCase {

    private static final Logger log = LoggerFactory.getLogger(UploadFileUseCase.class);
    private static final double RELEVANCE_THRESHOLD = 0.30;

    // Uploads are the core value loop — no cap. The gate is Mochi count,
    // not how much a student can teach a single Mochi.

    private final AvatarRepository avatarRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final WikiRepository wikiRepository;
    private final StorageService storageService;
    private final OcrPort ocrService;
    private final PdfTextExtractor pdfTextExtractor;
    private final RelevancePort relevancePort;
    private final CompileWikiUseCase compileWikiUseCase;
    private final ActivityLogService activityLogService;
    private final BadgeService badgeService;
    private final com.pally.domain.subscription.PremiumService premiumService;
    private final ConsentGuard consentGuard;
    private final ContentDeduplicator deduplicator;
    /// Background executor for wiki compilation so the HTTP upload response
    /// returns in seconds instead of blocking for 60–120s.
    private final Executor aiTaskExecutor;

    public UploadFileUseCase(
            AvatarRepository avatarRepository,
            KnowledgeRepository knowledgeRepository,
            WikiRepository wikiRepository,
            StorageService storageService,
            OcrPort ocrService,
            PdfTextExtractor pdfTextExtractor,
            RelevancePort relevancePort,
            CompileWikiUseCase compileWikiUseCase,
            ActivityLogService activityLogService,
            BadgeService badgeService,
            com.pally.domain.subscription.PremiumService premiumService,
            ConsentGuard consentGuard,
            ContentDeduplicator deduplicator,
            @Qualifier("aiTaskExecutor") Executor aiTaskExecutor) {
        this.avatarRepository    = avatarRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.wikiRepository      = wikiRepository;
        this.storageService      = storageService;
        this.ocrService          = ocrService;
        this.pdfTextExtractor    = pdfTextExtractor;
        this.relevancePort       = relevancePort;
        this.compileWikiUseCase  = compileWikiUseCase;
        this.activityLogService  = activityLogService;
        this.badgeService        = badgeService;
        this.premiumService      = premiumService;
        this.consentGuard        = consentGuard;
        this.deduplicator        = deduplicator;
        this.aiTaskExecutor      = aiTaskExecutor;
    }

    public UploadResult execute(String avatarId, String userId, MultipartFile file) {
        return execute(avatarId, userId, file, false);
    }

    public UploadResult execute(String avatarId, String userId, MultipartFile file, boolean skipRelevance) {
        // PDPA gate: uploading personal notes requires an ACTIVE account.
        consentGuard.requireActive(userId, "UPLOAD");

        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));


        String contentType = file.getContentType();
        KnowledgeFile.UploadType uploadType = resolveUploadType(contentType);
        String storageKey = buildStorageKey(avatarId, file.getOriginalFilename());

        // Persist file to storage
        try {
            storageService.store(storageKey, file.getInputStream(), file.getSize(), contentType);
        } catch (IOException e) {
            log.error("Storage failure for avatarId={}", avatarId, e);
            return new UploadResult.Failure("Storage error: " + e.getMessage(), e);
        }

        // Create a KnowledgeFile record in PROCESSING state
        KnowledgeFile kf = KnowledgeFile.create(avatarId, userId, file.getOriginalFilename(), storageKey, uploadType);
        kf = knowledgeRepository.save(kf);
        final String fileId = kf.getId();

        // Extract text
        String extractedText;
        int pageCount;
        try {
            if (uploadType == KnowledgeFile.UploadType.PDF) {
                var result = pdfTextExtractor.extract(file.getInputStream());
                extractedText = result.text();
                pageCount = result.pageCount();
            } else {
                extractedText = ocrService.extractText(
                        file.getBytes(), file.getContentType());
                pageCount = 1;
            }
        } catch (IOException e) {
            log.error("Extraction failure for fileId={}", fileId, e);
            kf.markFailed();
            knowledgeRepository.save(kf);
            return new UploadResult.Failure("Text extraction failed: " + e.getMessage(), e);
        }

        // Save extracted text + content hash for deduplication.
        kf.setExtractedText(extractedText);
        String contentHash = deduplicator.computeHash(extractedText);
        kf.setContentHash(contentHash);

        // Deduplication: reject exact duplicates; warn on near-duplicates.
        // Runs BEFORE relevance check to save the Claude API call.
        // DuplicateContentException is a PallyException → 409 handled by GlobalExceptionHandler.
        deduplicator.check(avatarId, extractedText, file.getOriginalFilename());

        // Relevance check (skippable when user explicitly opts in via "Add Anyway")
        if (!skipRelevance) {
            var avatar = avatarRepository.findById(avatarId)
                    .orElseThrow(() -> new AvatarNotFoundException(avatarId));
            String sample = TextSampler.sample(extractedText);

            // Pass existing wiki titles so the relevance checker has context.
            List<WikiPage> existingPages = wikiRepository.findByAvatarId(avatarId);
            String wikiSummary = existingPages.stream()
                    .map(p -> "- " + p.getTitle())
                    .collect(Collectors.joining("\n"));

            RelevanceScore rel = relevancePort.check(
                    avatar.getSubject().name(), wikiSummary, sample);

            if (rel.value() < RELEVANCE_THRESHOLD) {
                kf.markIrrelevant();
                knowledgeRepository.save(kf);
                log.info("File fileId={} marked irrelevant score={}", fileId, rel.value());
                return new UploadResult.RelevanceWarning(fileId, rel.value(), rel.reason());
            }
        } else {
            log.info("Skipping relevance check for fileId={} (user override)", fileId);
        }

        kf.markReady(pageCount);
        knowledgeRepository.save(kf);

        // Activity + first-upload badge (fast, run synchronously)
        activityLogService.log(userId, avatarId, ActivityLogService.TYPE_UPLOAD, 0, 0);
        badgeService.grantFirstAction(userId, BadgeService.BadgeType.FIRST_UPLOAD);

        // Wiki compilation is the slow step (30–120s per doc). Run it in the
        // background so the HTTP response returns well within Railway's 60s
        // proxy timeout. The frontend navigates to the success screen
        // immediately; the brain viewer populates as pages are written.
        final String capturedFileId = fileId;
        final int capturedPageCount = pageCount;
        aiTaskExecutor.execute(() -> {
            try {
                CompileWikiUseCase.CompileResult r = compileWikiUseCase.execute(avatarId);
                log.info("[Upload] Async compile done fileId={} pages={} compiled={}",
                        capturedFileId, capturedPageCount, r.pageTitles().size());
            } catch (Exception e) {
                log.error("[Upload] Async compile failed fileId={}", capturedFileId, e);
            }
        });

        log.info("[Upload] File accepted, compilation started in background fileId={} pages={}",
                fileId, pageCount);
        // Return immediately with an empty pageTitles list — the wiki viewer
        // will show pages as they're written by the background compile.
        return new UploadResult.Success(fileId, pageCount, List.of());
    }

    private KnowledgeFile.UploadType resolveUploadType(String contentType) {
        if (contentType != null && contentType.equalsIgnoreCase("application/pdf")) {
            return KnowledgeFile.UploadType.PDF;
        }
        return KnowledgeFile.UploadType.PHOTO;
    }

    private String buildStorageKey(String avatarId, String originalFilename) {
        String safeName = originalFilename != null ? originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_") : "file";
        return "avatars/" + avatarId + "/uploads/" + System.currentTimeMillis() + "_" + safeName;
    }
}
