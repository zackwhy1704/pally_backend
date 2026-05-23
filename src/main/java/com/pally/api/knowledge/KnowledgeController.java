package com.pally.api.knowledge;

import com.pally.api.knowledge.dto.KnowledgeFileResponse;
import com.pally.api.knowledge.dto.RelevanceCheckRequest;
import com.pally.api.knowledge.dto.RelevanceCheckResponse;
import com.pally.api.knowledge.dto.WikiCompileResponse;
import com.pally.api.knowledge.dto.WikiPageResponse;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.usecase.CheckRelevanceUseCase;
import com.pally.domain.knowledge.usecase.CompileWikiUseCase;
import com.pally.domain.knowledge.usecase.DeleteFileUseCase;
import com.pally.domain.knowledge.usecase.UploadFileUseCase;
import com.pally.domain.knowledge.usecase.UploadResult;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final KnowledgeRepository knowledgeRepository;
    private final KnowledgeMapper knowledgeMapper;
    private final WikiRepository wikiRepository;

    /**
     * Uploads a file to the avatar's knowledge base.
     * Runs OCR/extraction, relevance check, and triggers wiki compilation.
     *
     * @param userId   user identifier from {@code X-User-Id} header
     * @param avatarId avatar to attach the file to
     * @param file     the multipart file to upload
     * @return 201 Created on success, 200 OK with warning if content is irrelevant
     */
    @PostMapping("/files")
    public ResponseEntity<ApiResponse<Object>> uploadFile(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId,
            @RequestParam("file") MultipartFile file
    ) {
        UploadResult result = uploadFileUseCase.execute(avatarId, userId, file);

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
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId
    ) {
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
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId,
            @PathVariable String fileId
    ) {
        deleteFileUseCase.execute(fileId, userId);
        return ResponseEntity.noContent().build();
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
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody RelevanceCheckRequest request
    ) {
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
    public ResponseEntity<ApiResponse<WikiCompileResponse>> compileWiki(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId
    ) {
        CompileWikiUseCase.CompileResult result = compileWikiUseCase.execute(avatarId);
        int total = result.pagesCreated() + result.pagesUpdated();
        WikiCompileResponse response = new WikiCompileResponse(
                total,
                "Wiki compiled: %d page(s) created, %d page(s) updated"
                        .formatted(result.pagesCreated(), result.pagesUpdated())
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/wiki/pages")
    public ResponseEntity<ApiResponse<WikiPageResponse.ListResponse>> listWikiPages(
            @PathVariable String avatarId
    ) {
        List<WikiPage> pages = wikiRepository.findByAvatarId(avatarId);
        List<WikiPageResponse> responses = pages.stream()
                .map(WikiPageResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(new WikiPageResponse.ListResponse(responses)));
    }

    @GetMapping("/wiki/pages/{slug}")
    public ResponseEntity<ApiResponse<WikiPageResponse>> getWikiPage(
            @PathVariable String avatarId,
            @PathVariable String slug
    ) {
        WikiPage page = wikiRepository.findByAvatarIdAndSlug(avatarId, slug)
                .orElseThrow(() -> new BusinessException("Wiki page not found: " + slug, 404));
        return ResponseEntity.ok(ApiResponse.success(WikiPageResponse.from(page)));
    }

    @PatchMapping("/wiki/pages/{slug}/correction")
    public ResponseEntity<ApiResponse<WikiPageResponse>> applyCorrection(
            @PathVariable String avatarId,
            @PathVariable String slug,
            @RequestBody HumanCorrectionRequest request
    ) {
        WikiPage page = wikiRepository.findByAvatarIdAndSlug(avatarId, slug)
                .orElseThrow(() -> new BusinessException("Wiki page not found: " + slug, 404));
        page.applyHumanCorrection(request.correction());
        WikiPage saved = wikiRepository.save(page);
        return ResponseEntity.ok(ApiResponse.success(WikiPageResponse.from(saved)));
    }

    record HumanCorrectionRequest(String correction) {}
}
