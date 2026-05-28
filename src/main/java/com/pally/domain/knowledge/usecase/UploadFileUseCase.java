package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.RelevanceScore;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.RelevancePort;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.infrastructure.ocr.PdfTextExtractor;
import com.pally.infrastructure.ocr.TesseractOcrService;
import com.pally.infrastructure.storage.StorageService;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.util.TextSampler;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Use case: upload a file, run OCR/extraction, relevance-check, and ingest into the avatar's wiki.
 */
@Service
@RequiredArgsConstructor
public class UploadFileUseCase {

    private static final Logger log = LoggerFactory.getLogger(UploadFileUseCase.class);
    private static final double RELEVANCE_THRESHOLD = 0.30;

    private final AvatarRepository avatarRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final WikiRepository wikiRepository;
    private final StorageService storageService;
    private final TesseractOcrService ocrService;
    private final PdfTextExtractor pdfTextExtractor;
    private final RelevancePort relevancePort;
    private final CompileWikiUseCase compileWikiUseCase;
    private final ActivityLogService activityLogService;
    private final BadgeService badgeService;

    public UploadResult execute(String avatarId, String userId, MultipartFile file) {
        return execute(avatarId, userId, file, false);
    }

    public UploadResult execute(String avatarId, String userId, MultipartFile file, boolean skipRelevance) {
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
                extractedText = ocrService.extractText(file.getInputStream());
                pageCount = 1;
            }
        } catch (IOException e) {
            log.error("Extraction failure for fileId={}", fileId, e);
            kf.markFailed();
            knowledgeRepository.save(kf);
            return new UploadResult.Failure("Text extraction failed: " + e.getMessage(), e);
        }

        // Save extracted text on the KnowledgeFile so the wiki compiler can use it.
        // Without this the compiler only sees filename + ID and Claude invents
        // content from the filename — the root cause of "vague" tutor answers.
        kf.setExtractedText(extractedText);

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

        // Trigger wiki compilation and capture the new page titles so the
        // upload response can drive the post-upload "you learned X" screen.
        CompileWikiUseCase.CompileResult compileResult =
                compileWikiUseCase.execute(avatarId);

        // Activity + first-upload badge
        activityLogService.log(userId, avatarId, ActivityLogService.TYPE_UPLOAD, 0, 0);
        badgeService.grantFirstAction(userId, BadgeService.BadgeType.FIRST_UPLOAD);

        log.info("File uploaded and ingested fileId={} pages={} compiledPages={}",
                fileId, pageCount, compileResult.pageTitles().size());
        return new UploadResult.Success(fileId, pageCount, compileResult.pageTitles());
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
