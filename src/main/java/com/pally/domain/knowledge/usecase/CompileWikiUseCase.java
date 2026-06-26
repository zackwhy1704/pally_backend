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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    private final CompileJobStore compileJobStore;

    @Qualifier(AiTaskExecutorConfig.AI_TASK_EXECUTOR)
    private final ThreadPoolExecutor aiTaskExecutor;

    @Value("${compile.max-sync-chars:50000}")
    private int maxSyncChars;

    public record CompileResult(
            int pagesCreated,
            int pagesUpdated,
            List<String> pageTitles,
            String tierServed,
            int filesCompiled,
            int totalCharsCompiled,
            List<FailedPage> failedPages
    ) {
        public CompileResult(int pagesCreated, int pagesUpdated, List<String> pageTitles) {
            this(pagesCreated, pagesUpdated, pageTitles, "unknown", 0, 0, List.of());
        }

        public CompileResult(int pagesCreated, int pagesUpdated, List<String> pageTitles,
                             String tierServed, int filesCompiled, int totalCharsCompiled) {
            this(pagesCreated, pagesUpdated, pageTitles, tierServed, filesCompiled,
                    totalCharsCompiled, List.of());
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

        // Zero-source guard: if every new file has empty extracted text (e.g. a
        // stale image-only PDF uploaded before the empty-text guard existed, or
        // a scanned PDF with no text layer), the compiler can only ever return 0
        // pages. Skip the Gemini→Gemini→Haiku round-trip so a recompile — in
        // particular the startup reconciler — doesn't burn AI budget on every
        // deploy. The file stays READY; re-uploading replaces the empty text.
        if (totalChars == 0) {
            log.warn("[Pipeline:Compile] SKIP avatarId={} — {} new file(s) have 0 chars of "
                    + "extracted text; nothing to compile (likely a scanned/image-only PDF "
                    + "with no text layer). Re-upload as text or a clear photo.",
                    avatarId, newFiles.size());
            return new CompileResult(0, 0, List.of(), "skipped-empty-source", 0, 0);
        }

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

        // Set compiledBy on each file for provenance tracking
        for (KnowledgeFile f : newFiles) {
            f.setCompiledBy(tierServed);
            knowledgeRepository.save(f);
        }

        if (!outcome.failedPages().isEmpty()) {
            log.warn("[Pipeline:Compile] avatarId={} — {} page(s) failed to persist: {}",
                    avatarId, outcome.failedPages().size(), outcome.failedPages());
        }
        return new CompileResult(
                outcome.created(), outcome.updated(), outcome.pageTitles(),
                tierServed, newFiles.size(), totalChars, outcome.failedPages());
    }

    /**
     * Returns true if the total chars exceed the sync cap and should be
     * compiled asynchronously. Called by the controller to decide 200 vs 202.
     */
    public boolean shouldCompileAsync(String avatarId) {
        List<KnowledgeFile> readyFiles = knowledgeRepository.findByAvatarId(avatarId).stream()
                .filter(f -> f.getStatus() == KnowledgeFile.Status.READY)
                .toList();

        java.util.Set<String> compiled;
        try {
            compiled = new java.util.HashSet<>(
                    wikiPageSourceRepo.findCompiledFileIdsByAvatarId(avatarId));
        } catch (Exception e) {
            compiled = java.util.Set.of();
        }
        final java.util.Set<String> alreadyCompiledIds = compiled;

        int totalChars = readyFiles.stream()
                .filter(f -> !alreadyCompiledIds.contains(f.getId()))
                .mapToInt(f -> f.getExtractedText() != null ? f.getExtractedText().length() : 0)
                .sum();

        return totalChars > maxSyncChars;
    }

    /**
     * Starts an async compile job. Returns the job ID immediately.
     * The compile runs on the bounded AI pool with batch splitting.
     */
    public String executeAsync(String avatarId) {
        String jobId = UUID.randomUUID().toString().substring(0, 12);
        CompileJobStore.JobStatus initial = new CompileJobStore.JobStatus(
                jobId, avatarId, CompileJobStore.JobState.RUNNING,
                0, 0, null, null, List.of(), java.time.Instant.now());
        compileJobStore.put(jobId, initial);

        try {
            aiTaskExecutor.submit(() -> {
                try {
                    CompileResult result = executeBatched(avatarId, jobId);
                    compileJobStore.put(jobId,
                            compileJobStore.get(jobId).withDone(
                                    result.pagesCreated() + result.pagesUpdated(),
                                    result.pagesCreated() + result.pagesUpdated(),
                                    result.tierServed(),
                                    result.failedPages()));
                } catch (Exception e) {
                    log.error("[Pipeline:AsyncCompile] Job {} failed for avatarId={}",
                            jobId, avatarId, e);
                    CompileJobStore.JobStatus current = compileJobStore.get(jobId);
                    if (current != null) {
                        compileJobStore.put(jobId, current.withFailed(e.getMessage()));
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            compileJobStore.put(jobId, initial.withFailed("Queue full — try again in a moment"));
            throw new BusinessException(
                    "Mochi's busy compiling other brains — try again in a moment.", 503);
        }

        return jobId;
    }

    /**
     * Batched compile: splits files into batches under the char budget,
     * compiles each batch separately, and persists pages after each batch
     * so a failure on batch 3 doesn't lose batches 1-2.
     */
    CompileResult executeBatched(String avatarId, String jobId) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        List<KnowledgeFile> readyFiles = knowledgeRepository.findByAvatarId(avatarId).stream()
                .filter(f -> f.getStatus() == KnowledgeFile.Status.READY)
                .toList();

        java.util.Set<String> compiled;
        try {
            compiled = new java.util.HashSet<>(
                    wikiPageSourceRepo.findCompiledFileIdsByAvatarId(avatarId));
        } catch (Exception e) {
            compiled = java.util.Set.of();
        }
        final java.util.Set<String> alreadyCompiledIds = compiled;

        List<KnowledgeFile> newFiles = readyFiles.stream()
                .filter(f -> !alreadyCompiledIds.contains(f.getId()))
                .toList();

        if (newFiles.isEmpty()) {
            return new CompileResult(0, 0, List.of(), "skipped-all-compiled", 0, 0);
        }

        // Split into batches
        List<List<KnowledgeFile>> batches = splitIntoBatches(newFiles, maxSyncChars);
        log.info("[Pipeline:BatchCompile] avatarId={} totalFiles={} batches={}",
                avatarId, newFiles.size(), batches.size());

        int totalCreated = 0;
        int totalUpdated = 0;
        List<String> allTitles = new ArrayList<>();
        List<FailedPage> allFailedPages = new ArrayList<>();
        String lastTier = "unknown";
        int totalFilesCompiled = 0;
        int totalCharsCompiled = 0;

        for (int i = 0; i < batches.size(); i++) {
            List<KnowledgeFile> batch = batches.get(i);
            int batchChars = batch.stream()
                    .mapToInt(f -> f.getExtractedText() != null ? f.getExtractedText().length() : 0)
                    .sum();

            log.info("[Pipeline:BatchCompile] Batch {}/{}: {} files, {} chars",
                    i + 1, batches.size(), batch.size(), batchChars);

            try {
                List<WikiPage> existingWikiPages = wikiRepository.findByAvatarId(avatarId);
                WikiCompilerPort.CompileOutput output =
                        wikiCompiler.compileWithTier(avatar, batch, existingWikiPages);

                WikiPagePersistenceService.PersistOutcome outcome =
                        persistenceService.persistDrafts(avatar, output.drafts(), batch);

                totalCreated += outcome.created();
                totalUpdated += outcome.updated();
                allTitles.addAll(outcome.pageTitles());
                allFailedPages.addAll(outcome.failedPages());
                lastTier = output.tierServed();
                totalFilesCompiled += batch.size();
                totalCharsCompiled += batchChars;

                // Set compiledBy on each file
                for (KnowledgeFile f : batch) {
                    f.setCompiledBy(output.tierServed());
                    knowledgeRepository.save(f);
                }

                // Update job progress
                if (jobId != null) {
                    CompileJobStore.JobStatus current = compileJobStore.get(jobId);
                    if (current != null) {
                        compileJobStore.put(jobId, current.withProgress(
                                totalCreated + totalUpdated,
                                totalCreated + totalUpdated,
                                lastTier));
                    }
                }

                log.info("[Pipeline:BatchCompile] Batch {}/{} DONE: created={} updated={}",
                        i + 1, batches.size(), outcome.created(), outcome.updated());

            } catch (Exception e) {
                log.error("[Pipeline:BatchCompile] Batch {}/{} FAILED: {} — " +
                          "previous batches ({} pages) are safe",
                        i + 1, batches.size(), e.getMessage(),
                        totalCreated + totalUpdated);
                // Continue — partial persist: previous batches are already saved
            }
        }

        // Cache invalidation
        cacheInvalidationService.onWikiContentChanged(avatarId, cacheKeepAliveService);

        return new CompileResult(totalCreated, totalUpdated, allTitles,
                lastTier, totalFilesCompiled, totalCharsCompiled, allFailedPages);
    }

    /**
     * Splits files into batches where each batch's total chars is under the budget.
     */
    static List<List<KnowledgeFile>> splitIntoBatches(List<KnowledgeFile> files, int maxCharsPerBatch) {
        List<List<KnowledgeFile>> batches = new ArrayList<>();
        List<KnowledgeFile> currentBatch = new ArrayList<>();
        int currentChars = 0;

        for (KnowledgeFile file : files) {
            int fileChars = file.getExtractedText() != null ? file.getExtractedText().length() : 0;

            // If a single file exceeds the budget, it gets its own batch
            if (!currentBatch.isEmpty() && currentChars + fileChars > maxCharsPerBatch) {
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentChars = 0;
            }

            currentBatch.add(file);
            currentChars += fileChars;
        }

        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        return batches;
    }

}
