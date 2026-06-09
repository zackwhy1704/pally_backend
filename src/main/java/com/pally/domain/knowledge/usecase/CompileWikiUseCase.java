package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.infrastructure.ai.CacheInvalidationService;
import com.pally.infrastructure.ai.CacheKeepAliveService;
import com.pally.infrastructure.config.AiTaskExecutorConfig;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.WikiCompileException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Use case: compile an avatar's knowledge files into structured wiki pages via Claude.
 */
@Service
@RequiredArgsConstructor
public class CompileWikiUseCase {

    private static final Logger log = LoggerFactory.getLogger(CompileWikiUseCase.class);

    private final AvatarRepository avatarRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final WikiRepository wikiRepository;
    private final WikiCompilerPort wikiCompiler;
    private final CacheInvalidationService cacheInvalidationService;
    private final CacheKeepAliveService cacheKeepAliveService;
    private final WikiPagePersistenceService persistenceService;
    private final WikiPageSourceJpaRepository wikiPageSourceRepo;

    @Qualifier(AiTaskExecutorConfig.AI_TASK_EXECUTOR)
    private final ThreadPoolExecutor aiTaskExecutor;

    public record CompileResult(
            int pagesCreated,
            int pagesUpdated,
            List<String> pageTitles,
            String tierServed,
            int filesCompiled,
            int totalCharsCompiled
    ) {
        public CompileResult(int pagesCreated, int pagesUpdated, List<String> pageTitles) {
            this(pagesCreated, pagesUpdated, pageTitles, "unknown", 0, 0);
        }
    }

    /// Bounded variant — runs the compile on the {@link AiTaskExecutorConfig}
    /// pool so concurrent compiles can never exhaust the web tier or the
    /// Claude budget. A flooded queue surfaces a 503 to the caller via
    /// {@link RejectedExecutionException} → {@link BusinessException}, which
    /// the client treats as "try again in a moment".
    public CompileResult executeBounded(String avatarId) {
        try {
            Future<CompileResult> future =
                    aiTaskExecutor.submit(() -> execute(avatarId));
            // Cap the wait so a stuck queue can't park the request thread
            // forever. Matches the Claude stream/idle ceiling.
            return future.get(4, TimeUnit.MINUTES);
        } catch (RejectedExecutionException e) {
            throw new BusinessException(
                    "Mochi's busy compiling other brains — try again in a moment.",
                    503);
        } catch (TimeoutException e) {
            throw new BusinessException(
                    "Compile took too long. Please retry.", 504);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Compile interrupted", 500);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new WikiCompileException("Compile failed", cause);
        }
    }

    public CompileResult execute(String avatarId) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        // R6 — archive pages not retrieved in 60+ days so the active index
        // stays lean across a school year. Archived pages stay in the DB and
        // can be revived on next retrieval if a future query matches them.
        // Best-effort: never block a compile on stale-page housekeeping.
        try {
            int archived = wikiRepository.archiveStalePages(
                    avatarId,
                    java.time.Instant.now().minus(java.time.Duration.ofDays(60)));
            if (archived > 0) {
                log.info("[Harness] Archived {} stale pages for avatar={}",
                        archived, avatarId);
            }
        } catch (Exception e) {
            log.warn("[Harness] Stale-page archive failed (non-fatal): {}",
                    e.getMessage());
        }

        // ── Pipeline log: file inventory ─────────────────────────────────────
        List<KnowledgeFile> allFiles = knowledgeRepository.findByAvatarId(avatarId);
        long readyCount   = allFiles.stream().filter(f -> f.getStatus() == KnowledgeFile.Status.READY).count();
        long failedCount  = allFiles.stream().filter(f -> f.getStatus() == KnowledgeFile.Status.FAILED).count();
        long processingCount = allFiles.stream().filter(f -> f.getStatus() == KnowledgeFile.Status.PROCESSING).count();
        long existingPages   = wikiRepository.countActiveByAvatarId(avatarId);
        log.info("[Pipeline:Compile] avatarId={} files: total={} ready={} failed={} processing={} existingWikiPages={}",
                avatarId, allFiles.size(), readyCount, failedCount, processingCount, existingPages);

        List<KnowledgeFile> readyFiles = allFiles.stream()
                .filter(f -> f.getStatus() == KnowledgeFile.Status.READY)
                .toList();

        if (readyFiles.isEmpty()) {
            log.warn("[Pipeline:Compile] NO READY files for avatarId={} — compile skipped. " +
                     "Failed={} Processing={}. Re-upload or use /wiki/recompile to reset FAILED files.",
                     avatarId, failedCount, processingCount);
            // Archive all active pages since there are no ready files — the brain is empty.
            try {
                int archived = wikiRepository.archiveOrphanPages(avatarId, List.of());
                if (archived > 0) {
                    log.info("[Pipeline:Compile] Archived {} orphan pages (no ready files) for avatar={}",
                            archived, avatarId);
                }
            } catch (Exception e) {
                log.warn("[Pipeline:Compile] Orphan archive failed (non-fatal): {}", e.getMessage());
            }
            return new CompileResult(0, 0, List.of());
        }

        // ── Incremental compile: only feed NEW files to the AI compiler ──────
        // Files that already have wiki_page_sources entries have been successfully
        // compiled before — their content is already in wiki pages. Sending them
        // again wastes tokens, and on the Haiku fallback with a large corpus, it
        // causes the chunk explosion that trips the 4-minute timeout.
        java.util.Set<String> compiled;
        try {
            compiled = new java.util.HashSet<>(
                    wikiPageSourceRepo.findCompiledFileIdsByAvatarId(avatarId));
        } catch (Exception e) {
            log.warn("[Pipeline:Compile] Could not load compiled file IDs (compiling all): {}", e.getMessage());
            compiled = java.util.Set.of();
        }
        final java.util.Set<String> alreadyCompiledIds = compiled;

        List<KnowledgeFile> newFiles = readyFiles.stream()
                .filter(f -> !alreadyCompiledIds.contains(f.getId()))
                .toList();
        int skippedCount = readyFiles.size() - newFiles.size();

        if (newFiles.isEmpty() && !readyFiles.isEmpty()) {
            log.info("[Pipeline:Compile] All {} READY files already compiled — nothing new for avatarId={}",
                    readyFiles.size(), avatarId);
            return new CompileResult(0, 0, List.of(), "skipped-all-compiled",
                    0, 0);
        }

        int totalChars = newFiles.stream()
                .mapToInt(f -> f.getExtractedText() != null ? f.getExtractedText().length() : 0)
                .sum();
        log.info("[Pipeline:Compile] START avatarId={} newFiles={} skipped={} totalChars={} names={}",
                avatarId, newFiles.size(), skippedCount, totalChars,
                newFiles.stream().map(KnowledgeFile::getFileName).toList());

        List<WikiPage> existingWikiPages = wikiRepository.findByAvatarId(avatarId);

        WikiCompilerPort.CompileOutput compileOutput;
        try {
            compileOutput = wikiCompiler.compileWithTier(avatar, newFiles, existingWikiPages);
        } catch (Exception e) {
            log.error("[Pipeline:Compile] Compile FAILED for avatarId={}: {} — " +
                      "Check the [Gemini]/[Claude-xxx] log above for the error body.",
                      avatarId, e.getMessage());
            throw new WikiCompileException("Wiki compilation failed for avatar " + avatarId, e);
        }

        List<WikiCompilerPort.WikiPageDraft> drafts = compileOutput.drafts();
        String tierServed = compileOutput.tierServed();
        log.info("[Pipeline:Compile] {} produced {} draft pages (tier={}) for avatarId={}: titles={}",
                tierServed, drafts.size(), tierServed, avatarId,
                drafts.stream().map(WikiCompilerPort.WikiPageDraft::title).toList());

        WikiPagePersistenceService.PersistOutcome outcome =
                persistenceService.persistDrafts(avatar, drafts, newFiles);

        log.info("[Pipeline:Compile] DONE avatarId={} created={} updated={} titles={}",
                avatarId, outcome.created(), outcome.updated(), outcome.pageTitles());

        // Archive wiki pages whose slugs were NOT produced by this compile run.
        // This makes the brain a pure function of the current READY files —
        // pages from deleted files disappear automatically.
        try {
            int archived = wikiRepository.archiveOrphanPages(avatarId, outcome.producedSlugs());
            if (archived > 0) {
                log.info("[Pipeline:Compile] Archived {} orphan pages for avatar={} (slugs no longer produced by compile)",
                        archived, avatarId);
            }
        } catch (Exception e) {
            log.warn("[Pipeline:Compile] Orphan archive failed (non-fatal): {}", e.getMessage());
        }

        // Invalidate Block 3 cache so next request picks up the new content.
        // Best-effort cache work stays outside the persistence transaction.
        cacheInvalidationService.onWikiContentChanged(avatarId, cacheKeepAliveService);

        return new CompileResult(
                outcome.created(), outcome.updated(), outcome.pageTitles(),
                tierServed, newFiles.size(), totalChars);
    }

}
