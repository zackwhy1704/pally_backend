package com.pally.api.knowledge;

import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.knowledge.KnowledgeService;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.dto.KnowledgeFileResponse;
import com.pally.domain.knowledge.dto.RelevanceCheckRequest;
import com.pally.domain.knowledge.dto.RelevanceCheckResponse;
import com.pally.domain.knowledge.dto.WikiPageResponse;
import com.pally.domain.knowledge.usecase.UploadResult;
import com.pally.shared.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * REST controller for knowledge file and wiki management.
 *
 * <p>All endpoints are scoped under a specific avatar:
 * {@code /api/v1/avatars/{avatarId}/...}
 *
 * <p>Thin HTTP layer: each method parses the request, delegates to
 * {@link KnowledgeService}, and maps the result to {@link ApiResponse} +
 * status. All ownership guards, repository access, and business logic live in
 * the service.
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}")
@RequiredArgsConstructor
public class KnowledgeController {

    /// Hard cap matches Railway request-size limits; the upload screen also
    /// pre-checks on the client, so getting here means the client cap drifted.
    private static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;

    private final KnowledgeService knowledgeService;
    private final WikiPageResponseMapper wikiPageResponseMapper;
    private final ConsentGuard consentGuard;

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
        UploadResult result = knowledgeService.uploadFile(userId, avatarId, file, skipRelevance);

        return switch (result) {
            case UploadResult.Success s -> ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(ApiResponse.created(s));
            case UploadResult.Segmented seg -> ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(ApiResponse.created(seg));   // client shows the chapter picker (has `chunks`)
            case UploadResult.RelevanceWarning w -> ResponseEntity
                    .ok(ApiResponse.success(w));
            case UploadResult.Failure f -> ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(f.message(), 500));
        };
    }

    @GetMapping("/files")
    public ResponseEntity<ApiResponse<List<KnowledgeFileResponse>>> listFiles(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        return ResponseEntity.ok(ApiResponse.success(knowledgeService.listFiles(userId, avatarId)));
    }

    /** List chapter chunks (picker + locked-chapter surface) + the compile allowance. */
    @GetMapping("/chapters")
    public ResponseEntity<ApiResponse<com.pally.domain.knowledge.usecase.GetChaptersUseCase.ChaptersResult>> chapters(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        return ResponseEntity.ok(ApiResponse.success(knowledgeService.chapters(userId, avatarId)));
    }

    /** Pick one chapter chunk to compile. 402 CHUNK_COMPILE when over the monthly
     *  allowance (same paywall shape as the upload cap). */
    @PostMapping("/files/{chunkId}/compile")
    public ResponseEntity<ApiResponse<com.pally.domain.knowledge.usecase.CompileChunkUseCase.Result>> compileChunk(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String chunkId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                knowledgeService.compileChunk(userId, avatarId, chunkId)));
    }

    @DeleteMapping("/files/{fileId}")
    public ResponseEntity<Void> deleteFile(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String fileId
    ) {
        knowledgeService.deleteFile(userId, avatarId, fileId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/files/{fileId}/progress")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fileProgress(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String fileId
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(knowledgeService.fileProgress(userId, avatarId, fileId)));
    }

    @PostMapping("/relevance")
    public ResponseEntity<ApiResponse<RelevanceCheckResponse>> checkRelevance(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody RelevanceCheckRequest request
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(knowledgeService.checkRelevance(userId, avatarId, request)));
    }

    @PostMapping("/wiki/compile")
    public ResponseEntity<ApiResponse<Object>> compileWiki(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        consentGuard.requireAiAllowed(userId); // PDPA/PDPC: gate AI wiki compilation
        KnowledgeService.CompileOutcome outcome = knowledgeService.compileWiki(userId, avatarId);
        if (outcome.async()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(outcome.asyncBody()));
        }
        return ResponseEntity.ok(ApiResponse.success(outcome.syncBody()));
    }

    @GetMapping("/wiki/compile/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> compileStatus(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(knowledgeService.compileStatus(userId, avatarId)));
    }

    @PostMapping("/wiki/recompile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recompileWiki(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        consentGuard.requireAiAllowed(userId); // PDPA/PDPC: gate AI wiki recompilation
        return ResponseEntity.accepted()
                .body(ApiResponse.success(knowledgeService.recompileWiki(userId, avatarId)));
    }

    @GetMapping("/wiki/pages")
    public ResponseEntity<ApiResponse<WikiPageResponse.ListResponse>> listWikiPages(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        KnowledgeService.WikiPagesResult result = knowledgeService.listWikiPages(userId, avatarId);
        List<WikiPageResponse> responses = result.entries().stream()
                .map(e -> wikiPageResponseMapper.toResponse(e.page(), e.sourceFileNames()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(new WikiPageResponse.ListResponse(responses)));
    }

    @GetMapping("/wiki/pages/{slug}")
    public ResponseEntity<ApiResponse<WikiPageResponse>> getWikiPage(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String slug
    ) {
        WikiPage page = knowledgeService.getWikiPage(userId, avatarId, slug);
        return ResponseEntity.ok(ApiResponse.success(wikiPageResponseMapper.toResponse(page)));
    }

    @PatchMapping("/wiki/pages/{slug}/correction")
    public ResponseEntity<ApiResponse<WikiPageResponse>> applyCorrection(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String slug,
            @RequestBody HumanCorrectionRequest request
    ) {
        WikiPage page = knowledgeService.applyCorrection(userId, avatarId, slug, request.correction());
        return ResponseEntity.ok(ApiResponse.success(wikiPageResponseMapper.toResponse(page)));
    }

    record HumanCorrectionRequest(String correction) {}

    @PatchMapping("/files/{fileId}/review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewFile(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String fileId,
            @RequestBody Map<String, Object> body
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(knowledgeService.reviewFile(userId, avatarId, fileId, body)));
    }
}
