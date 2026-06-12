package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.OcrQualityGate;
import com.pally.domain.knowledge.RelevanceScore;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.RelevancePort;
import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.knowledge.ContentDeduplicator;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.infrastructure.ai.WikiRecompileScheduler;
import com.pally.infrastructure.ocr.PdfTextExtractor;
import com.pally.domain.knowledge.port.OcrPort;
import com.pally.infrastructure.storage.StorageService;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.util.TextSampler;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
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

    // Explicit allowlist of MIME types we accept. Anything not on this list is
    // rejected with a clear 400 before any processing or storage occurs.
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "text/plain",
            "text/markdown",
            "text/x-markdown",
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif"
    );

    // Uploads are the core value loop — no cap. The gate is Mochi count,
    // not how much a student can teach a single Mochi.

    private final AvatarRepository avatarRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final WikiRepository wikiRepository;
    private final StorageService storageService;
    private final OcrPort ocrService;
    private final PdfTextExtractor pdfTextExtractor;
    private final RelevancePort relevancePort;
    private final WikiRecompileScheduler recompileScheduler;
    private final ActivityLogService activityLogService;
    private final BadgeService badgeService;
    private final com.pally.domain.subscription.PremiumService premiumService;
    private final ConsentGuard consentGuard;
    private final ContentDeduplicator deduplicator;
    private final AvatarSlotGuard avatarSlotGuard;
    private final OcrQualityGate ocrQualityGate;

    public UploadFileUseCase(
            AvatarRepository avatarRepository,
            KnowledgeRepository knowledgeRepository,
            WikiRepository wikiRepository,
            StorageService storageService,
            OcrPort ocrService,
            PdfTextExtractor pdfTextExtractor,
            RelevancePort relevancePort,
            WikiRecompileScheduler recompileScheduler,
            ActivityLogService activityLogService,
            BadgeService badgeService,
            com.pally.domain.subscription.PremiumService premiumService,
            ConsentGuard consentGuard,
            ContentDeduplicator deduplicator,
            AvatarSlotGuard avatarSlotGuard,
            OcrQualityGate ocrQualityGate) {
        this.avatarRepository    = avatarRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.wikiRepository      = wikiRepository;
        this.storageService      = storageService;
        this.ocrService          = ocrService;
        this.pdfTextExtractor    = pdfTextExtractor;
        this.relevancePort       = relevancePort;
        this.recompileScheduler  = recompileScheduler;
        this.activityLogService  = activityLogService;
        this.badgeService        = badgeService;
        this.premiumService      = premiumService;
        this.consentGuard        = consentGuard;
        this.deduplicator        = deduplicator;
        this.avatarSlotGuard     = avatarSlotGuard;
        this.ocrQualityGate      = ocrQualityGate;
    }

    public UploadResult execute(String avatarId, String userId, MultipartFile file) {
        return execute(avatarId, userId, file, false);
    }

    public UploadResult execute(String avatarId, String userId, MultipartFile file, boolean skipRelevance) {
        // PDPA gate: uploading personal notes requires an ACTIVE account.
        consentGuard.requireActive(userId, "UPLOAD");

        // Third-party AI consent gate (Apple 5.1.2 / PDPA overseas transfer).
        // Always enforced — see ConsentGuard.requireAiConsent Javadoc.
        consentGuard.requireAiConsent(userId);

        // PDPC 2024 age gate: under-13 users need a linked+consented parent.
        // No-op for 13+ users (target audience), so nothing changes for them.
        consentGuard.requireGuardianIfUnder13(userId);

        // Fix 2: Slot guard — locked avatars cannot receive new knowledge.
        // NOTE: DELETE paths are exempt (AvatarSlotGuard Javadoc).
        avatarSlotGuard.requireActive(avatarId, userId);


        String contentType = file.getContentType();

        // MIME allowlist — reject unsupported types before any storage or LLM call.
        String normalised = contentType != null ? contentType.toLowerCase().split(";")[0].trim() : "";
        if (!ALLOWED_MIME_TYPES.contains(normalised)) {
            log.warn("[Pipeline:Upload] Rejected unsupported MIME type={} avatarId={}", contentType, avatarId);
            return new UploadResult.Failure(
                    "Unsupported file type '" + contentType + "'. "
                    + "Please upload a PDF, plain text (.txt), or a photo (JPEG/PNG/WEBP).", null);
        }

        KnowledgeFile.UploadType uploadType = resolveUploadType(normalised);
        String storageKey = buildStorageKey(avatarId, file.getOriginalFilename());

        // Read bytes ONCE — MultipartFile.getInputStream() returns the same
        // underlying stream each call; once storage drains it, PDF extraction
        // gets EOF and produces empty text (Bug: entire pipeline starved).
        // Using file.getBytes() guarantees a fresh in-memory byte array that
        // both storage and extraction can read independently.
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            log.error("Failed to read multipart bytes for avatarId={}", avatarId, e);
            return new UploadResult.Failure("Could not read the uploaded file.", e);
        }

        // Persist file to storage
        try {
            storageService.store(storageKey,
                    new java.io.ByteArrayInputStream(fileBytes),
                    fileBytes.length, contentType);
        } catch (Exception e) {
            log.error("Storage failure for avatarId={}", avatarId, e);
            return new UploadResult.Failure("Storage error: " + e.getMessage(), e);
        }

        // Create a KnowledgeFile record in PROCESSING state
        KnowledgeFile kf = KnowledgeFile.create(avatarId, userId, file.getOriginalFilename(), storageKey, uploadType);
        kf = knowledgeRepository.save(kf);
        final String fileId = kf.getId();

        log.info("[Pipeline:Upload] START fileId={} fileName={} type={} sizeBytes={}",
                fileId, file.getOriginalFilename(), uploadType, fileBytes.length);

        // Extract text — both paths now use the pre-read byte array so the
        // multipart stream is never touched again.
        String extractedText;
        int pageCount;
        try {
            if (uploadType == KnowledgeFile.UploadType.PDF) {
                var result = pdfTextExtractor.extractFromBytes(fileBytes);
                extractedText = result.text();
                pageCount = result.pageCount();
                log.info("[Pipeline:Upload] PDF extracted fileId={} chars={} pages={}",
                        fileId, extractedText.length(), pageCount);
            } else if (uploadType == KnowledgeFile.UploadType.TEXT) {
                // Plain text / markdown — decode bytes directly, no LLM call needed.
                extractedText = new String(fileBytes, StandardCharsets.UTF_8);
                pageCount = 1;
                log.info("[Pipeline:Upload] TEXT decoded fileId={} chars={}", fileId, extractedText.length());
            } else {
                extractedText = ocrService.extractText(fileBytes, contentType);
                pageCount = 1;
                log.info("[Pipeline:Upload] OCR extracted fileId={} chars={}", fileId, extractedText.length());
            }
        } catch (IOException e) {
            log.error("Extraction failure for fileId={}", fileId, e);
            kf.markFailed();
            knowledgeRepository.save(kf);
            return new UploadResult.Failure("Text extraction failed: " + e.getMessage(), e);
        }

        // Empty-text guard: if extraction produced nothing (e.g. a scanned
        // image-only PDF with no selectable text), fail explicitly rather
        // than marking READY and compiling an empty wiki page.
        if (extractedText == null || extractedText.isBlank()) {
            log.warn("[Upload] Zero text extracted from fileId={} type={}", fileId, uploadType);
            kf.markFailed();
            knowledgeRepository.save(kf);
            String ocrFailMsg = uploadType == KnowledgeFile.UploadType.PDF
                    ? "Couldn't read any text from this PDF. It may contain only scanned images with no selectable text. "
                      + "Try: (1) use a text-based PDF, (2) copy-paste the text instead, "
                      + "or (3) take a clear photo of the pages."
                    : "Couldn't read text from this photo. Common causes: "
                      + "(1) too dark or blurry — retake in good lighting, "
                      + "(2) the page is at a steep angle — hold the camera directly above, "
                      + "(3) handwriting is very light — try higher contrast. "
                      + "Tip: crop to just the notes before uploading.";
            return new UploadResult.Failure(ocrFailMsg, null);
        }

        // ── OCR Quality Gate (image uploads only) ───────────────────────────
        // Text and PDF uploads bypass the quality gate: their text is clean by definition.
        OcrQualityGate.QualityResult qualityResult = null;
        if (uploadType == KnowledgeFile.UploadType.PHOTO) {
            qualityResult = ocrQualityGate.evaluate(extractedText, fileBytes.length);
            log.info("[Pipeline:Upload] OCR quality={} fileId={} reason={}",
                    qualityResult.quality(), fileId, qualityResult.reason());

            if (qualityResult.quality() == OcrQualityGate.Quality.REJECTED) {
                kf.markFailed();
                knowledgeRepository.save(kf);
                return new UploadResult.Failure(qualityResult.reason(), null);
            }
            // Use cleaned text from the quality gate
            extractedText = qualityResult.cleanedText();
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
        // Fix 2: Skip relevance for STEM image uploads — OCR garbles math notation
        // so the relevance model can't judge content quality from text alone.
        if (!skipRelevance && isStemImageUpload(uploadType, avatarId)) {
            log.info("[Upload] Skipping relevance check for STEM image upload fileId={}", fileId);
            skipRelevance = true;
        }

        if (!skipRelevance) {
            var avatar = avatarRepository.findById(avatarId)
                    .orElseThrow(() -> new AvatarNotFoundException(avatarId));
            String sample = TextSampler.sample(extractedText);

            // Pass existing wiki titles so the relevance checker has context.
            List<WikiPage> existingPages = wikiRepository.findByAvatarId(avatarId);
            String wikiSummary = existingPages.stream()
                    .map(p -> "- " + p.getTitle())
                    .collect(Collectors.joining("\n"));

            RelevanceScore rel;
            try {
                rel = relevancePort.check(avatar.getSubject().name(), wikiSummary, sample);
            } catch (Exception e) {
                // Relevance check is a best-effort gate. If Claude is unavailable
                // (timeout, parse error, circuit open), accept the content so a
                // transient API failure never blocks a valid upload with a raw 500.
                log.warn("[Upload] Relevance check threw for fileId={} — treating as relevant", fileId, e);
                rel = new RelevanceScore(1.0, "Check unavailable");
            }

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

        // Debounced wiki recompile: coalesces rapid uploads into a single compile
        // and marks the avatar as PENDING_RECOMPILE immediately so the UI can show
        // a spinner. Actual compile fires after the debounce window.
        recompileScheduler.requestRecompile(avatarId);
        log.info("[Upload] Recompile requested via debounce scheduler fileId={}", fileId);

        log.info("[Upload] File accepted, compilation started in background fileId={} pages={}",
                fileId, pageCount);
        // Return immediately with an empty pageTitles list — the wiki viewer
        // will show pages as they're written by the background compile.
        // Include OCR quality info for image uploads so the client can decide
        // whether to show a review screen.
        String quality = qualityResult != null ? qualityResult.quality().name() : "GOOD";
        String qualityReason = qualityResult != null ? qualityResult.reason() : null;
        String returnedExtractedText = qualityResult != null ? qualityResult.cleanedText() : null;
        return new UploadResult.Success(fileId, pageCount, List.of(),
                quality, qualityReason, returnedExtractedText);
    }

    private KnowledgeFile.UploadType resolveUploadType(String normalisedMime) {
        return switch (normalisedMime) {
            case "application/pdf" -> KnowledgeFile.UploadType.PDF;
            case "text/plain", "text/markdown", "text/x-markdown" -> KnowledgeFile.UploadType.TEXT;
            default -> KnowledgeFile.UploadType.PHOTO;
        };
    }

    private String buildStorageKey(String avatarId, String originalFilename) {
        String safeName = originalFilename != null ? originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_") : "file";
        return "avatars/" + avatarId + "/uploads/" + System.currentTimeMillis() + "_" + safeName;
    }

    /**
     * Returns true if the upload is an image AND the avatar's subject is STEM.
     * STEM image OCR garbles equations, making relevance scores unreliable.
     */
    private boolean isStemImageUpload(KnowledgeFile.UploadType uploadType, String avatarId) {
        if (uploadType != KnowledgeFile.UploadType.PHOTO) return false;
        return avatarRepository.findById(avatarId)
                .map(a -> isStemSubject(a.getSubject()))
                .orElse(false);
    }

    static boolean isStemSubject(com.pally.domain.avatar.Subject subject) {
        return subject == com.pally.domain.avatar.Subject.MATHS
            || subject == com.pally.domain.avatar.Subject.SCIENCE
            || subject == com.pally.domain.avatar.Subject.CODING;
    }
}
