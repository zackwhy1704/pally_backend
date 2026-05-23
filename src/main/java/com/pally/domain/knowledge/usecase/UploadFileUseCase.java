package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.RelevanceScore;
import com.pally.domain.knowledge.port.RelevancePort;
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

/**
 * Use case: upload a file, run OCR/extraction, relevance-check, and ingest into the avatar's wiki.
 */
@Service
@RequiredArgsConstructor
public class UploadFileUseCase {

    private static final Logger log = LoggerFactory.getLogger(UploadFileUseCase.class);
    private static final double RELEVANCE_THRESHOLD = 0.45;

    private final AvatarRepository avatarRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final StorageService storageService;
    private final TesseractOcrService ocrService;
    private final PdfTextExtractor pdfTextExtractor;
    private final RelevancePort relevancePort;
    private final CompileWikiUseCase compileWikiUseCase;

    public UploadResult execute(String avatarId, String userId, MultipartFile file) {
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

        // Relevance check
        var avatar = avatarRepository.findById(avatarId).orElseThrow(() -> new AvatarNotFoundException(avatarId));
        String sample = TextSampler.sample(extractedText);
        RelevanceScore rel = relevancePort.check(avatar.getSubject().name(), "", sample);

        if (rel.value() < RELEVANCE_THRESHOLD) {
            kf.markIrrelevant();
            knowledgeRepository.save(kf);
            log.info("File fileId={} marked irrelevant score={}", fileId, rel.value());
            return new UploadResult.RelevanceWarning(fileId, rel.value(), rel.reason());
        }

        kf.markReady(pageCount);
        knowledgeRepository.save(kf);

        // Trigger async wiki compilation
        compileWikiUseCase.execute(avatarId);

        log.info("File uploaded and ingested fileId={} pages={}", fileId, pageCount);
        return new UploadResult.Success(fileId, pageCount);
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
