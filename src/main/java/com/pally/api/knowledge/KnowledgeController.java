package com.pally.api.knowledge;

import com.pally.api.knowledge.dto.KnowledgeFileResponse;
import com.pally.api.knowledge.dto.RelevanceCheckRequest;
import com.pally.api.knowledge.dto.RelevanceCheckResponse;
import com.pally.api.knowledge.dto.WikiCompileResponse;
import com.pally.api.knowledge.dto.WikiPageResponse;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.usecase.CheckRelevanceUseCase;
import com.pally.domain.knowledge.usecase.CompileJobStore;
import com.pally.domain.knowledge.usecase.CompileWikiUseCase;
import com.pally.domain.knowledge.usecase.DeleteFileUseCase;
import com.pally.domain.knowledge.usecase.UploadFileUseCase;
import com.pally.domain.knowledge.usecase.UploadResult;
import com.pally.infrastructure.ai.WikiRecompileScheduler;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * REST controller for knowledge file and wiki management.
 *
 * <p>All endpoints are scoped under a specific avatar:
 * {@code /api/v1/avatars/{avatarId}/...}
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}")
@RequiredArgsConstructor
public class KnowledgeController {

    private final UploadFileUseCase uploadFileUseCase;
    private final DeleteFileUseCase deleteFileUseCase;
    private final CheckRelevanceUseCase checkRelevanceUseCase;
    private final CompileWikiUseCase compileWikiUseCase;
    private final CompileJobStore compileJobStore;
    private final WikiRecompileScheduler recompileScheduler;
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeMapper knowledgeMapper;
    private final WikiRepository wikiRepository;
    private final AvatarRepository avatarRepository;
    private final AvatarSlotGuard avatarSlotGuard;
    private final WikiPageSourceJpaRepository wikiPageSourceRepo;

    /**
     * Uploads a file to the avatar's knowledge base.
     * Runs OCR/extraction, relevance check, and triggers wiki compilation.
     *
     * @param userId   user identifier from {@code X-User-Id} header
     * @param avatarId avatar to attach the file to
     * @param file     the multipart file to upload
     * @return 201 Created on success, 200 OK with warning if content is irrelevant
     */
    /// Hard cap matches Railway request-size limits; the upload screen also
    /// pre-checks on the client, so getting here means the client cap drifted.
    private static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;

    @PostMapping("/files")
    public ResponseEntity<ApiResponse<Object>> uploadFile(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "skipRelevance", defaultValue = "false") boolean skipRelevance
    ) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("File is empty", 400));
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            long limitMb = MAX_UPLOAD_BYTES / (1024 * 1024);
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(ApiResponse.error(
                            "File is too large (max " + limitMb + "MB).", 413));
        }
        UploadResult result = uploadFileUseCase.execute(avatarId, userId, file, skipRelevance);

        return switch (result) {
            case UploadResult.Success s -> ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(ApiResponse.created(s));
            case UploadResult.RelevanceWarning w -> ResponseEntity
                    .ok(ApiResponse.success(w));
            case UploadResult.Failure f -> ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(f.message(), 500));
        };
    }

    /**
     * Lists all knowledge files attached to an avatar.
     *
     * @param userId   user identifier from {@code X-User-Id} header
     * @param avatarId avatar identifier
     * @return 200 OK with list of knowledge file summaries
     */
    @GetMapping("/files")
    public ResponseEntity<ApiResponse<List<KnowledgeFileResponse>>> listFiles(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        // Ownership guard: confirm the avatar belongs to this user before returning files.
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new com.pally.shared.exception.AvatarNotFoundException(avatarId));

        List<KnowledgeFile> files = knowledgeRepository.findByAvatarId(avatarId);
        return ResponseEntity.ok(ApiResponse.success(knowledgeMapper.toResponseList(files)));
    }

    /**
     * Deletes a knowledge file by ID.
     *
     * @param userId   user identifier from {@code X-User-Id} header
     * @param avatarId avatar identifier
     * @param fileId   knowledge file identifier
     * @return 204 No Content
     */
    @DeleteMapping("/files/{fileId}")
    public ResponseEntity<Void> deleteFile(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String fileId
    ) {
        deleteFileUseCase.execute(fileId, userId);
        return ResponseEntity.noContent().build();
    }

    /// Lightweight progress probe for the upload screen so it can render
    /// "Reading your notes…" → "Studying…" → "Done!" without subscribing
    /// to a stream. Compile is fast enough that polling every 1-2s is fine.
    @GetMapping("/files/{fileId}/progress")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> fileProgress(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String fileId
    ) {
        KnowledgeFile file = knowledgeRepository.findById(fileId)
                .orElseThrow(() -> new com.pally.shared.exception.BusinessException(
                        "File not found", 404));
        if (!file.getAvatarId().equals(avatarId)) {
            throw new com.pally.shared.exception.BusinessException(
                    "File does not belong to this avatar", 403);
        }
        int percent;
        String stage;
        switch (file.getStatus()) {
            case READY      -> { percent = 100; stage = "Done"; }
            case FAILED     -> { percent = 0;   stage = "Failed"; }
            case IRRELEVANT -> { percent = 0;   stage = "Off-topic"; }
            default         -> { percent = 35;  stage = "Reading your notes…"; }
        }
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("fileId", file.getId());
        body.put("status", file.getStatus().name());
        body.put("progressPercent", percent);
        body.put("stage", stage);
        body.put("pageCount", file.getPageCount());
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    /**
     * Checks whether a content sample is relevant to the avatar's subject domain.
     *
     * @param userId   user identifier from {@code X-User-Id} header
     * @param avatarId avatar identifier
     * @param request  content sample to evaluate
     * @return 200 OK with relevance score and reason
     */
    @PostMapping("/relevance")
    public ResponseEntity<ApiResponse<RelevanceCheckResponse>> checkRelevance(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody RelevanceCheckRequest request
    ) {
        // Ownership guard: prevent cross-user subject inference from relevance scores.
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new com.pally.shared.exception.AvatarNotFoundException(avatarId));

        CheckRelevanceUseCase.RelevanceResult result =
                checkRelevanceUseCase.execute(avatarId, request.contentSample());
        RelevanceCheckResponse response = new RelevanceCheckResponse(
                result.score(), result.reason(), result.relevant()
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Triggers wiki compilation from the avatar's READY knowledge files.
     *
     * @param userId   user identifier from {@code X-User-Id} header
     * @param avatarId avatar identifier
     * @return 200 OK with number of pages compiled
     */
    @PostMapping("/wiki/compile")
    public ResponseEntity<ApiResponse<Object>> compileWiki(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        // Check if content exceeds sync cap — if so, run async and return 202
        if (compileWikiUseCase.shouldCompileAsync(avatarId)) {
            String jobId = compileWikiUseCase.executeAsync(avatarId);
            java.util.Map<String, Object> asyncBody = new java.util.HashMap<>();
            asyncBody.put("compileJobId", jobId);
            asyncBody.put("message", "Content is large — compiling in background. Poll /wiki/compile/status for progress.");
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(asyncBody));
        }

        // Bounded synchronous path — capped concurrent compiles
        CompileWikiUseCase.CompileResult result =
                compileWikiUseCase.executeBounded(avatarId);
        int total = result.pagesCreated() + result.pagesUpdated();
        WikiCompileResponse response = new WikiCompileResponse(
                total,
                result.pageTitles(),
                "Wiki compiled: %d page(s) created, %d page(s) updated"
                        .formatted(result.pagesCreated(), result.pagesUpdated()),
                result.tierServed()
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Poll endpoint for async compile job status.
     */
    @GetMapping("/wiki/compile/status")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> compileStatus(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        CompileJobStore.JobStatus status = compileJobStore.findByAvatarId(avatarId);
        if (status == null) {
            return ResponseEntity.ok(ApiResponse.success(java.util.Map.of(
                    "state", "NONE",
                    "message", "No compile job found for this avatar"
            )));
        }
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("jobId", status.jobId());
        body.put("state", status.state().name());
        body.put("pagesCompiled", status.pagesCompiled());
        body.put("pagesTotal", status.pagesTotal());
        body.put("compiledBy", status.compiledBy());
        if (status.errorMessage() != null) {
            body.put("error", status.errorMessage());
        }
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    /**
     * Retry wiki compilation for all FAILED or READY-but-empty files.
     *
     * <p>Resets FAILED files back to READY so they'll be included in the next compile,
     * then bypasses the debounce and fires the compile immediately (recompileNow).
     * Returns 202 Accepted immediately — the compile runs asynchronously.
     */
    @PostMapping("/wiki/recompile")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> recompileWiki(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        // Fix 2: Slot guard — locked avatars cannot be manually recompiled.
        // Ownership is checked inside the guard.
        avatarSlotGuard.requireActive(avatarId, userId);

        // Reset FAILED files to READY so they'll be included in the compile
        List<com.pally.domain.knowledge.KnowledgeFile> failed =
                knowledgeRepository.findByAvatarId(avatarId).stream()
                        .filter(f -> f.getStatus() == com.pally.domain.knowledge.KnowledgeFile.Status.FAILED
                                && f.getExtractedText() != null
                                && !f.getExtractedText().isBlank())
                        .toList();
        for (var f : failed) {
            f.markReady(f.getPageCount() > 0 ? f.getPageCount() : 1);
            knowledgeRepository.save(f);
        }
        if (!failed.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(KnowledgeController.class)
                    .info("[Recompile] Reset {} FAILED files to READY for avatarId={}", failed.size(), avatarId);
        }

        // Bypass debounce — fire immediately
        recompileScheduler.recompileNow(avatarId);
        org.slf4j.LoggerFactory.getLogger(KnowledgeController.class)
                .info("[Recompile] Queued via recompileNow for avatarId={}", avatarId);

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("message", "Brain recompile queued" +
                (failed.isEmpty() ? "" : " (reset " + failed.size() + " failed file(s))"));
        body.put("avatarId", avatarId);
        return ResponseEntity.accepted().body(ApiResponse.success(body));
    }

    @GetMapping("/wiki/pages")
    public ResponseEntity<ApiResponse<WikiPageResponse.ListResponse>> listWikiPages(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        // Ownership guard: verify the avatar belongs to the requesting user
        // before returning wiki pages. Without this, any authenticated user
        // who knows an avatarId could read another user's notes.
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new com.pally.shared.exception.AvatarNotFoundException(avatarId));

        List<WikiPage> pages = wikiRepository.findByAvatarId(avatarId);
        // Fix 3: attach provenance (source file names) to each page response
        List<WikiPageResponse> responses = pages.stream()
                .map(page -> {
                    List<String> sourceFileNames = wikiPageSourceRepo
                            .findSourceFileNamesByWikiPageId(page.getId());
                    return WikiPageResponse.from(page, sourceFileNames);
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(new WikiPageResponse.ListResponse(responses)));
    }

    @GetMapping("/wiki/pages/{slug}")
    public ResponseEntity<ApiResponse<WikiPageResponse>> getWikiPage(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String slug
    ) {
        // Ownership guard: same pattern as listWikiPages — prevents User B from
        // reading User A's notes by guessing an avatarId + slug.
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new com.pally.shared.exception.AvatarNotFoundException(avatarId));

        WikiPage page = wikiRepository.findByAvatarIdAndSlug(avatarId, slug)
                .orElseThrow(() -> new BusinessException("Wiki page not found: " + slug, 404));
        return ResponseEntity.ok(ApiResponse.success(WikiPageResponse.from(page)));
    }

    @PatchMapping("/wiki/pages/{slug}/correction")
    public ResponseEntity<ApiResponse<WikiPageResponse>> applyCorrection(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String slug,
            @RequestBody HumanCorrectionRequest request
    ) {
        // Ownership guard: prevents User B from editing User A's wiki pages.
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new com.pally.shared.exception.AvatarNotFoundException(avatarId));

        WikiPage page = wikiRepository.findByAvatarIdAndSlug(avatarId, slug)
                .orElseThrow(() -> new BusinessException("Wiki page not found: " + slug, 404));
        page.applyHumanCorrection(request.correction());
        WikiPage saved = wikiRepository.save(page);
        return ResponseEntity.ok(ApiResponse.success(WikiPageResponse.from(saved)));
    }

    record HumanCorrectionRequest(String correction) {}
}
