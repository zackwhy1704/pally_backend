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
import com.pally.shared.exception.OcrUnavailableException;
import com.pally.shared.util.TextSampler;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.pally.domain.knowledge.Segment;
import java.util.ArrayList;
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
    private final com.pally.domain.subscription.UploadQuotaGuard uploadQuotaGuard;
    private final DocumentSegmentationService segmentationService;

    /// Segment TRIGGER — a valid document above this extracted-char size is split
    /// into pickable chapter chunks instead of compiling whole (the quality unit,
    /// ~25 pages). Below it, byte-identical to the pre-chunking behaviour.
    @org.springframework.beans.factory.annotation.Value("${compile.segment-trigger-chars:50000}")
    private int segmentTriggerChars;

    /// Pathological-file SAFETY bound — NOT a quality ceiling. Only a genuinely
    /// broken/adversarial file (~2500 pages) is rejected here; a large-but-real
    /// document is segmented, never rejected for size. (The old 600k ceiling was a
    /// quality unit wearing a safety hat; those two jobs are now separated.)
    @org.springframework.beans.factory.annotation.Value("${compile.upload-reject-chars:5000000}")
    private int uploadRejectChars;

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
            OcrQualityGate ocrQualityGate,
            com.pally.domain.subscription.UploadQuotaGuard uploadQuotaGuard,
            DocumentSegmentationService segmentationService) {
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
        this.uploadQuotaGuard    = uploadQuotaGuard;
        this.segmentationService = segmentationService;
    }

    public UploadResult execute(String avatarId, String userId, MultipartFile file) {
        return execute(avatarId, userId, file, false);
    }

    public UploadResult execute(String avatarId, String userId, MultipartFile file, boolean skipRelevance) {
        // Child-data ingress (the ONE guard, default-deny) — before any model call or
        // DB write. Blocks a pending under-13 with PARENTAL_CONSENT_PENDING / unknown
        // age with AGE_DECLARATION_REQUIRED. Then the separate AI-transfer gate.
        consentGuard.requireChildDataIngressConsent(userId);
        consentGuard.requireAiConsent(userId);

        // Fix 2: Slot guard — locked avatars cannot receive new knowledge.
        // NOTE: DELETE paths are exempt (AvatarSlotGuard Javadoc).
        avatarSlotGuard.requireActive(avatarId, userId);

        // FREE-tier upload cap — the compile is the expensive op, so cap accepted
        // uploads by tier. Enforced HERE (before storage/OCR) so a capped user
        // fails fast with a 402 UPGRADE_REQUIRED (the client's paywall shape).
        uploadQuotaGuard.requireUploadQuota(userId);


        String contentType = file.getContentType();

        // MIME allowlist — reject unsupported types before any storage or LLM call.
        String normalised = contentType != null ? contentType.toLowerCase().split(";")[0].trim() : "";
        if (!ALLOWED_MIME_TYPES.contains(normalised)) {
            log.warn("[Pipeline:Upload] Rejected unsupported MIME type={} avatarId={}", contentType, avatarId);
            return UploadResult.Failure.badInput(
                    "Unsupported file type '" + contentType + "'. "
                    + "Please upload a PDF, plain text (.txt), or a photo (JPEG/PNG/WEBP).");
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
        } catch (OcrUnavailableException e) {
            log.warn("[Upload] OCR unavailable for fileId={}: {}", fileId, e.getMessage());
            kf.markFailed();
            knowledgeRepository.save(kf);
            return new UploadResult.Failure(
                    "Couldn't read text from this photo — our image reading service is temporarily unavailable. "
                    + "Please try again in a few minutes, or copy-paste the text instead.", null);
        } catch (IOException e) {
            log.error("Extraction failure for fileId={}", fileId, e);
            kf.markFailed();
            knowledgeRepository.save(kf);
            // Corrupt/encrypted/malformed PDF operating on an in-memory byte array —
            // a content problem, not a server I/O fault. 422, not 500.
            return UploadResult.Failure.badInput("Text extraction failed: " + e.getMessage());
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
            return UploadResult.Failure.badInput(ocrFailMsg);
        }

        // Pathological-file SAFETY bound only (not a quality/size ceiling). A large-
        // but-real document is NOT rejected here — it is segmented into pickable
        // chapters further down. This reject fires only for a genuinely broken or
        // adversarial file that would blow extraction memory/storage.
        if (uploadRejectChars > 0 && extractedText.length() > uploadRejectChars) {
            int estPages = Math.max(1, extractedText.length() / 1800); // ~1800 chars/page
            log.warn("[Upload] REJECTED fileId={} — {} chars (~{} pages) exceeds hard bound {}",
                    fileId, extractedText.length(), estPages, uploadRejectChars);
            kf.markFailed();
            knowledgeRepository.save(kf);
            return UploadResult.Failure.badInput(
                    "This file is extremely large (~" + estPages + " pages) and can't be processed. "
                    + "Please upload a smaller document.");
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
                return UploadResult.Failure.badInput(qualityResult.reason());
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

        // Relevance check (skippable only when the user explicitly opts in via
        // "Add Anyway"). F2 fix: STEM photo uploads are NO LONGER skipped wholesale.
        // OCR garbles math notation so the numeric TOPIC score is unreliable for a
        // photo — but the studyMaterial classifier ("is this educational content at
        // all, or a receipt/form/selfie?") is robust to garbling. So a STEM photo
        // still runs the check; we just don't enforce the topic score on it. This
        // keeps legit homework photos while rejecting a receipt photo (the QA-1.2
        // false-accept), whose title previously leaked into the NEXT upload's
        // relevance prompt (the cross-file "reason" bleed).
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

            // A non-study-material upload (receipt/invoice/form/selfie) is ALWAYS
            // rejected, on EVERY subject — a receipt is not notes even on a
            // topic-bounded avatar. (Previously topic-bounded subjects ignored
            // studyMaterial and gated on the topic score alone → the F2 hole.)
            // Topic-bounded subjects ADDITIONALLY reject clearly off-topic content
            // via the numeric score — except a STEM photo, whose OCR-derived score
            // is untrustworthy (only its studyMaterial verdict is enforced).
            if (shouldRejectRelevance(avatar.getSubject(), uploadType, rel)) {
                kf.markIrrelevant();
                knowledgeRepository.save(kf);
                log.info("File fileId={} marked irrelevant subject={} score={} studyMaterial={}",
                        fileId, avatar.getSubject(), rel.value(), rel.studyMaterial());
                return new UploadResult.RelevanceWarning(fileId, rel.value(), rel.reason());
            }
        } else {
            log.info("Skipping relevance check for fileId={} (user override)", fileId);
        }

        // ── Chapter-chunking ────────────────────────────────────────────────
        // A large-but-valid document is SPLIT into pickable chapter chunks instead
        // of compiling whole. The parent becomes SEGMENTED (holds the full text,
        // compile-ignored); each child is PENDING_CHUNK (compile-ignored) until the
        // student PICKS it. Nothing compiles here — no recompile is scheduled — so an
        // unpicked chunk costs only its share of the one-time extraction. Children are
        // created programmatically (they bypass the dedup gate by construction) and
        // carry their OWN text slice + hash, so siblings can never collide.
        if (extractedText.length() > segmentTriggerChars) {
            List<Segment> segments = segmentationService.segment(
                    fileBytes, uploadType, extractedText, pageCount, avatarId);
            if (segments.size() >= 2) {
                kf.markReady(pageCount);   // set page count, then move out of the compile sweep
                kf.markSegmented();
                knowledgeRepository.save(kf);

                List<UploadResult.ChunkInfo> chunkInfos = new ArrayList<>();
                for (Segment seg : segments) {
                    int chunkPages = Math.max(1, seg.pageTo() - seg.pageFrom() + 1);
                    // INTENTIONAL dedup bypass: children are created programmatically
                    // and NEVER routed through deduplicator.check() (the exact/Jaccard
                    // near-dup gate that ran on the parent above). Siblings share the
                    // parent's provenance by construction, so re-checking them against
                    // each other is wrong: adjacent chapters (windowed at overlap=0)
                    // are legitimately similar at their shared boundary and MUST NOT be
                    // rejected as duplicates. We still store a per-chunk content_hash
                    // (of the chunk's own slice) so the downstream content-change gate
                    // works per-chunk — distinct slices hash distinctly, no collision.
                    KnowledgeFile child = KnowledgeFile.createChunk(
                            kf, seg.title(), seg.pageFrom(), seg.pageTo(), chunkPages, seg.text());
                    child.setContentHash(deduplicator.computeHash(seg.text()));
                    KnowledgeFile saved = knowledgeRepository.save(child);
                    chunkInfos.add(new UploadResult.ChunkInfo(
                            saved.getId(), seg.title(), seg.pageFrom(), seg.pageTo(), chunkPages));
                }

                activityLogService.log(userId, avatarId, ActivityLogService.TYPE_UPLOAD, 0, 0);
                badgeService.grantFirstAction(userId, BadgeService.BadgeType.FIRST_UPLOAD);
                log.info("[Upload] SEGMENTED fileId={} into {} chunks — no eager compile", fileId,
                        chunkInfos.size());
                return new UploadResult.Segmented(fileId, chunkInfos);
            }
            // <2 segments (e.g. one dense chapter) → not worth a picker; compile whole.
            log.info("[Upload] fileId={} over trigger but produced <2 segments — compiling whole", fileId);
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
        int extractedChars = extractedText != null ? extractedText.length() : 0;
        return new UploadResult.Success(fileId, pageCount, List.of(),
                quality, qualityReason, returnedExtractedText, extractedChars);
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
    static boolean isStemSubject(com.pally.domain.avatar.Subject subject) {
        return subject == com.pally.domain.avatar.Subject.MATHS
            || subject == com.pally.domain.avatar.Subject.SCIENCE
            || subject == com.pally.domain.avatar.Subject.CODING;
    }

    /**
     * F2 relevance gate (pure, unit-tested). Reject when the upload is not study
     * material at all (receipt/invoice/form/selfie) on ANY subject; ADDITIONALLY
     * reject clearly off-topic content on a topic-bounded subject via the numeric
     * score — EXCEPT a STEM photo, whose OCR-derived topic score is untrustworthy
     * (only its studyMaterial verdict is enforced). This closes the QA-1.2
     * receipt-photo false-accept: previously STEM photos skipped relevance entirely
     * AND topic-bounded subjects ignored studyMaterial.
     */
    static boolean shouldRejectRelevance(com.pally.domain.avatar.Subject subject,
                                         KnowledgeFile.UploadType uploadType,
                                         RelevanceScore rel) {
        boolean stemPhoto = uploadType == KnowledgeFile.UploadType.PHOTO
                && isStemSubject(subject);
        boolean notStudyMaterial = !rel.studyMaterial();
        boolean offTopic = !stemPhoto
                && subject.isTopicallyBounded()
                && rel.value() < RELEVANCE_THRESHOLD;
        return notStudyMaterial || offTopic;
    }
}
