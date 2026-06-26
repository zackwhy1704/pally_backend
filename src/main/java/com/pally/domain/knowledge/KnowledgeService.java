package com.pally.domain.knowledge;

import com.pally.domain.knowledge.KnowledgeMapper;
import com.pally.domain.knowledge.dto.KnowledgeFileResponse;
import com.pally.domain.knowledge.dto.RelevanceCheckRequest;
import com.pally.domain.knowledge.dto.RelevanceCheckResponse;
import com.pally.domain.knowledge.dto.WikiCompileResponse;
import com.pally.domain.knowledge.dto.WikiPageResponse;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
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
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Application service for knowledge file + wiki management — owns all logic and
 * repository access so {@link KnowledgeController} stays a thin HTTP delegator.
 *
 * <p>Every method enforces the same per-avatar ownership / centre-material guards
 * the controller used to run inline, and returns the exact data the controller
 * wraps, so the HTTP contract is byte-identical.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeService {

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

    /** Domain carrier for wiki pages with their source file provenance. */
    public record WikiPageWithSources(WikiPage page, List<String> sourceFileNames) {}
    public record WikiPagesResult(List<WikiPageWithSources> entries) {
        public WikiPageResponse.ListResponse toListResponse() {
            return new WikiPageResponse.ListResponse(
                entries.stream().map(e -> WikiPageResponse.from(e.page(), e.sourceFileNames())).toList()
            );
        }
    }

    /** Outcome of a compile request: async (202 + job body) or sync (200 + result). */
    public record CompileOutcome(boolean async, Map<String, Object> asyncBody,
                                 WikiCompileResponse syncBody) {}

    // ── Upload ──────────────────────────────────────────────────────────────

    public UploadResult uploadFile(String userId, String avatarId, MultipartFile file,
                                   boolean skipRelevance) {
        assertMaterialMutable(avatarId, userId);
        return uploadFileUseCase.execute(avatarId, userId, file, skipRelevance);
    }

    public List<KnowledgeFileResponse> listFiles(String userId, String avatarId) {
        requireOwnedAvatar(avatarId, userId);
        List<KnowledgeFile> files = knowledgeRepository.findByAvatarId(avatarId);
        return knowledgeMapper.toResponseList(files);
    }

    public void deleteFile(String userId, String avatarId, String fileId) {
        assertMaterialMutable(avatarId, userId);
        deleteFileUseCase.execute(fileId, userId);
    }

    public Map<String, Object> fileProgress(String userId, String avatarId, String fileId) {
        KnowledgeFile file = knowledgeRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException("File not found", 404));
        if (!file.getAvatarId().equals(avatarId)) {
            throw new BusinessException("File does not belong to this avatar", 403);
        }
        int percent;
        String stage;
        switch (file.getStatus()) {
            case READY      -> { percent = 100; stage = "Done"; }
            case FAILED     -> { percent = 0;   stage = "Failed"; }
            case IRRELEVANT -> { percent = 0;   stage = "Off-topic"; }
            default         -> { percent = 35;  stage = "Reading your notes…"; }
        }
        Map<String, Object> body = new HashMap<>();
        body.put("fileId", file.getId());
        body.put("status", file.getStatus().name());
        body.put("progressPercent", percent);
        body.put("stage", stage);
        body.put("pageCount", file.getPageCount());
        return body;
    }

    // ── Relevance ─────────────────────────────────────────────────────────────

    public RelevanceCheckResponse checkRelevance(String userId, String avatarId,
                                                 RelevanceCheckRequest request) {
        requireOwnedAvatar(avatarId, userId);
        CheckRelevanceUseCase.RelevanceResult result =
                checkRelevanceUseCase.execute(avatarId, request.contentSample());
        return new RelevanceCheckResponse(result.score(), result.reason(), result.relevant(),
                result.studyMaterial());
    }

    // ── Compile ────────────────────────────────────────────────────────────────

    public CompileOutcome compileWiki(String userId, String avatarId) {
        assertMaterialMutable(avatarId, userId);
        // Check if content exceeds sync cap — if so, run async and return 202
        if (compileWikiUseCase.shouldCompileAsync(avatarId)) {
            String jobId = compileWikiUseCase.executeAsync(avatarId);
            Map<String, Object> asyncBody = new HashMap<>();
            asyncBody.put("compileJobId", jobId);
            asyncBody.put("message", "Content is large — compiling in background. Poll /wiki/compile/status for progress.");
            return new CompileOutcome(true, asyncBody, null);
        }

        // Bounded synchronous path — capped concurrent compiles. A1: claim the
        // per-avatar gate so an explicit compile never runs in parallel with a
        // debounced recompile for the same avatar (that race caused duplicate-key
        // failures on wiki_pages / learning_module). If a compile is already in
        // flight, don't start a parallel one — mark the avatar dirty (so the latest
        // content recompiles afterwards) and return 202 "in progress".
        if (!recompileScheduler.tryBeginExternalCompile(avatarId)) {
            recompileScheduler.markDirty(avatarId);
            Map<String, Object> body = new HashMap<>();
            body.put("inProgress", true);
            body.put("message",
                    "A compile is already in progress for this brain — poll /wiki/compile/status.");
            return new CompileOutcome(true, body, null);
        }
        CompileWikiUseCase.CompileResult result;
        try {
            result = compileWikiUseCase.executeBounded(avatarId);
        } finally {
            recompileScheduler.endExternalCompile(avatarId);
        }
        int total = result.pagesCreated() + result.pagesUpdated();
        String message = "Wiki compiled: %d page(s) created, %d page(s) updated"
                .formatted(result.pagesCreated(), result.pagesUpdated());
        if (!result.failedPages().isEmpty()) {
            message += ", %d page(s) failed".formatted(result.failedPages().size());
        }
        WikiCompileResponse response = new WikiCompileResponse(
                total,
                result.pageTitles(),
                message,
                result.tierServed(),
                result.failedPages()
        );
        return new CompileOutcome(false, null, response);
    }

    public Map<String, Object> compileStatus(String userId, String avatarId) {
        CompileJobStore.JobStatus status = compileJobStore.findByAvatarId(avatarId);
        if (status == null) {
            Map<String, Object> none = new HashMap<>();
            none.put("state", "NONE");
            none.put("message", "No compile job found for this avatar");
            return none;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", status.jobId());
        body.put("state", status.state().name());
        body.put("pagesCompiled", status.pagesCompiled());
        body.put("pagesTotal", status.pagesTotal());
        body.put("compiledBy", status.compiledBy());
        if (status.failedPages() != null && !status.failedPages().isEmpty()) {
            body.put("pagesFailed", status.failedPages().size());
            body.put("failedPages", status.failedPages());
        }
        if (status.errorMessage() != null) {
            body.put("error", status.errorMessage());
        }
        return body;
    }

    public Map<String, Object> recompileWiki(String userId, String avatarId) {
        assertMaterialMutable(avatarId, userId);
        // Slot guard — locked avatars cannot be manually recompiled (ownership checked inside).
        avatarSlotGuard.requireActive(avatarId, userId);

        // Reset FAILED files to READY so they'll be included in the compile
        List<KnowledgeFile> failed = knowledgeRepository.findByAvatarId(avatarId).stream()
                .filter(f -> f.getStatus() == KnowledgeFile.Status.FAILED
                        && f.getExtractedText() != null
                        && !f.getExtractedText().isBlank())
                .toList();
        for (var f : failed) {
            f.markReady(f.getPageCount() > 0 ? f.getPageCount() : 1);
            knowledgeRepository.save(f);
        }
        if (!failed.isEmpty()) {
            log.info("[Recompile] Reset {} FAILED files to READY for avatarId={}", failed.size(), avatarId);
        }

        // Bypass debounce — fire immediately
        recompileScheduler.recompileNow(avatarId);
        log.info("[Recompile] Queued via recompileNow for avatarId={}", avatarId);

        Map<String, Object> body = new HashMap<>();
        body.put("message", "Brain recompile queued" +
                (failed.isEmpty() ? "" : " (reset " + failed.size() + " failed file(s))"));
        body.put("avatarId", avatarId);
        return body;
    }

    // ── Wiki pages ───────────────────────────────────────────────────────────

    public WikiPagesResult listWikiPages(String userId, String avatarId) {
        requireOwnedAvatar(avatarId, userId);
        List<WikiPage> pages = wikiRepository.findByAvatarId(avatarId);
        List<WikiPageWithSources> entries = pages.stream()
                .map(page -> new WikiPageWithSources(
                        page,
                        wikiPageSourceRepo.findSourceFileNamesByWikiPageId(page.getId())))
                .toList();
        return new WikiPagesResult(entries);
    }

    public WikiPage getWikiPage(String userId, String avatarId, String slug) {
        requireOwnedAvatar(avatarId, userId);
        return wikiRepository.findByAvatarIdAndSlug(avatarId, slug)
                .orElseThrow(() -> new BusinessException("Wiki page not found: " + slug, 404));
    }

    public WikiPage applyCorrection(String userId, String avatarId, String slug,
                                    String correction) {
        requireOwnedAvatar(avatarId, userId);
        assertMaterialMutable(avatarId, userId);
        WikiPage page = wikiRepository.findByAvatarIdAndSlug(avatarId, slug)
                .orElseThrow(() -> new BusinessException("Wiki page not found: " + slug, 404));
        page.applyHumanCorrection(correction);
        return wikiRepository.save(page);
    }

    // ── File review (OCR text) ─────────────────────────────────────────────────

    public Map<String, Object> reviewFile(String userId, String avatarId, String fileId,
                                          Map<String, Object> body) {
        requireOwnedAvatar(avatarId, userId);
        assertMaterialMutable(avatarId, userId);

        KnowledgeFile file = knowledgeRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException("File not found", 404));
        if (!file.getAvatarId().equals(avatarId)) {
            throw new BusinessException("File does not belong to this avatar", 403);
        }

        String action = (String) body.get("action");
        if (action == null || (!action.equals("APPROVE") && !action.equals("EDIT"))) {
            throw new BusinessException("action must be APPROVE or EDIT", 400);
        }

        if ("EDIT".equals(action)) {
            String editedText = (String) body.get("editedText");
            if (editedText == null || editedText.isBlank()) {
                throw new BusinessException("editedText is required for EDIT action", 400);
            }
            file.setExtractedText(editedText.strip());
            log.info("[Review] File {} text edited by user, {} chars", fileId, editedText.strip().length());
        } else {
            log.info("[Review] File {} approved as-is by user", fileId);
        }

        // Ensure file is in READY status so it gets compiled
        if (file.getStatus() != KnowledgeFile.Status.READY) {
            file.markReady(file.getPageCount() > 0 ? file.getPageCount() : 1);
        }
        knowledgeRepository.save(file);

        // Trigger recompile to pick up the (possibly edited) text
        recompileScheduler.requestRecompile(avatarId);

        Map<String, Object> response = new HashMap<>();
        response.put("fileId", fileId);
        response.put("action", action);
        response.put("message", "EDIT".equals(action)
                ? "Text updated — recompiling brain." : "Text approved — recompiling brain.");
        return response;
    }

    // ── Guards ──────────────────────────────────────────────────────────────

    /** Ownership guard: the avatar must exist AND belong to the user, else 404. */
    private void requireOwnedAvatar(String avatarId, String userId) {
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));
    }

    /**
     * Defence-in-depth read-only guard for centre class materials. A student's
     * class-bound CENTRE_CLASS avatar is study-only — its materials are managed by
     * the centre, not the student.
     *
     * <p>The discriminator is {@code corpusAvatarId}, NOT {@code classId}: a
     * student's class avatar POINTS at the shared corpus ({@code corpusAvatarId != null}),
     * whereas the teacher's class CORPUS avatar — the one notes are actually uploaded
     * and compiled into — carries a {@code classId} (set deliberately so its modules
     * aren't orphaned) but has {@code corpusAvatarId == null}. Gating on classId
     * alone froze the corpus too, so teachers could never build any class content
     * (the centre-side "no content ever created" bug). The endpoint's own ownership
     * filter already restricts this to the avatar's owner. Reading/studying is
     * unaffected — this only gates mutations.
     */
    private void assertMaterialMutable(String avatarId, String userId) {
        var avatar = avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException("Avatar not found", 404));
        if (avatar.isCentreClass() && avatar.getCorpusAvatarId() != null) {
            throw new BusinessException(
                    "CENTRE_MATERIAL_READ_ONLY: class materials are managed by your centre.",
                    403);
        }
    }
}
