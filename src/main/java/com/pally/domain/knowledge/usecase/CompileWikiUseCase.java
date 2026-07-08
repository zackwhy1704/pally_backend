package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.util.TextWindower;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private final DocumentSegmentationService segmentationService;

    @Qualifier(AiTaskExecutorConfig.AI_TASK_EXECUTOR)
    private final ThreadPoolExecutor aiTaskExecutor;

    @Value("${compile.max-sync-chars:50000}")
    private int maxSyncChars;

    @Value("${compile.segment-trigger-chars:50000}")
    private int segmentTriggerChars;

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
        Future<CompileResult> future = null;
        try {
            future = aiTaskExecutor.submit(() -> execute(avatarId));
            // Cap the wait so a stuck queue can't park the request thread
            // forever. Matches the Claude stream/idle ceiling.
            return future.get(4, TimeUnit.MINUTES);
        } catch (RejectedExecutionException e) {
            throw new BusinessException(
                    "Mochi's busy compiling other brains — try again in a moment.",
                    503);
        } catch (TimeoutException e) {
            // Best-effort cleanup: signal the timed-out compile to stop so it doesn't
            // keep running orphaned past the 504. This is NOT a guaranteed kill — the
            // Claude call is a WebClient/Netty request with its own 180s response
            // timeout, so the interrupt unblocks the executor thread while the in-flight
            // HTTP call self-limits. Stacked compiles are ALREADY prevented upstream by
            // the caller's per-avatar single-flight gate (KnowledgeService →
            // WikiRecompileScheduler.tryBeginExternalCompile / inFlight); this is
            // orphan cleanup, not the stacking guard.
            if (future != null) {
                future.cancel(true);
            }
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

        // "Needs compile" = compiled_by NULL (completion marker), so a file left
        // incomplete by a prior segment failure is re-run, not skipped. (The
        // sources-based alreadyCompiledIds is still used below for orphan-archive.)
        List<KnowledgeFile> newFiles = readyFiles.stream()
                .filter(f -> f.getCompiledBy() == null)
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

        // Archive wiki pages whose slugs are NOT owned by any currently-READY file.
        //
        // BUG FIX: incremental compile only processes newFiles, so outcome.producedSlugs()
        // only contains slugs from this run. Previously, already-compiled files' pages
        // were NOT in the surviving set and got archived on every incremental upload.
        // Fix: union this run's produced slugs with slugs owned by previously-compiled
        // READY files — pages are only archived when their source file is no longer READY.
        try {
            List<String> alreadyCompiledReadyFileIds = readyFiles.stream()
                    .map(KnowledgeFile::getId)
                    .filter(alreadyCompiledIds::contains)
                    .toList();

            List<String> existingReadySlugs = alreadyCompiledReadyFileIds.isEmpty()
                    ? List.of()
                    : wikiPageSourceRepo.findActiveSlugsByFileIds(alreadyCompiledReadyFileIds);

            Set<String> allSurvivingSlugs = new HashSet<>(outcome.producedSlugs());
            allSurvivingSlugs.addAll(existingReadySlugs);

            int archived = wikiRepository.archiveOrphanPages(avatarId, new ArrayList<>(allSurvivingSlugs));
            if (archived > 0) {
                log.info("[Pipeline:Compile] Archived {} orphan pages for avatar={} "
                        + "(surviving slugs: {} new + {} from prior files)",
                        archived, avatarId, outcome.producedSlugs().size(), existingReadySlugs.size());
            }
        } catch (Exception e) {
            log.warn("[Pipeline:Compile] Orphan archive failed (non-fatal): {}", e.getMessage());
        }

        // Invalidate Block 3 cache so next request picks up the new content.
        // Best-effort cache work stays outside the persistence transaction.
        cacheInvalidationService.onWikiContentChanged(avatarId, cacheKeepAliveService);

        // Set compiledBy only when the compile actually produced pages. A 0-page
        // result (model degraded to empty without throwing) must NOT mark the files
        // "done" — else they're skipped forever with an empty wiki (silent loss).
        if (outcome.created() + outcome.updated() > 0) {
            for (KnowledgeFile f : newFiles) {
                f.markCompiled(tierServed);   // stamps compiled_by + compiled_at
                knowledgeRepository.save(f);
            }
        } else {
            log.warn("[Pipeline:Compile] avatarId={} — compile produced ZERO pages; leaving "
                    + "{} file(s) re-compilable (not marked compiled).", avatarId, newFiles.size());
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

        // Same "needs compile" definition as the compile paths (compiled_by NULL),
        // so routing (sync vs async) and compilation agree on what's outstanding.
        int totalChars = readyFiles.stream()
                .filter(f -> f.getCompiledBy() == null)
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

        // "Already compiled" = compiled_by is set (a true COMPLETION marker), NOT
        // merely "has a wiki_page_sources row". A segmented file whose compile
        // failed mid-run has source rows for the done segments but compiled_by
        // stays NULL — so it is re-compiled here instead of being silently skipped
        // as done (the partial-data-loss bug). Idempotent: pages merge by slug.
        List<KnowledgeFile> newFiles = readyFiles.stream()
                .filter(f -> f.getCompiledBy() == null)
                .toList();

        // LEGACY-FILE SWEEP GUARD — the total money-leak fix. A pre-existing oversized
        // READY file (uploaded before chunking, or any top-level file that slipped past
        // the upload-time split) must NEVER compile WHOLE here — that would eagerly burn
        // the whole-file cost (~$2+) before anyone ever sees a picker. Segment it into
        // PENDING_CHUNK children instead; the parent becomes SEGMENTED and drops out of
        // this sweep, so nothing compiles until a chunk is picked. Idempotent (a file
        // that already has children is skipped) and children-only (parentFileId==null).
        newFiles = segmentOversizedLegacyFiles(avatarId, newFiles);

        if (newFiles.isEmpty()) {
            return new CompileResult(0, 0, List.of(), "skipped-all-compiled", 0, 0);
        }

        // Split large files WITHIN the file first, then batch. A single big file
        // (e.g. a 157-page, ~400k-char book) would otherwise be one indivisible
        // compile unit → the compiler's internal chunking explodes into hundreds
        // of sequential AI calls and the job times out. Segmenting into
        // ~maxSyncChars windows keeps every compile call bounded AND lets the loop
        // persist after each segment, so progress is saved incrementally.
        List<KnowledgeFile> units = windowOversizedFiles(newFiles, maxSyncChars);
        List<List<KnowledgeFile>> batches = splitIntoBatches(units, maxSyncChars);
        log.info("[Pipeline:BatchCompile] avatarId={} totalFiles={} units(after-segmenting)={} batches={}",
                avatarId, newFiles.size(), units.size(), batches.size());

        int totalCreated = 0;
        int totalUpdated = 0;
        List<String> allTitles = new ArrayList<>();
        List<FailedPage> allFailedPages = new ArrayList<>();
        String lastTier = "unknown";
        int totalFilesCompiled = 0;
        int totalCharsCompiled = 0;
        // fileIds with at least one FAILED segment — these must NOT be marked
        // compiled (compiled_by stays NULL) so the next run re-compiles them.
        java.util.Set<String> failedFileIds = new java.util.HashSet<>();
        // fileIds that produced ≥1 page in some batch — only these are "done".
        java.util.Set<String> producedFileIds = new java.util.HashSet<>();

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

                // A batch that produced ≥1 page makes its files "productive". A file
                // that produces ZERO pages across ALL its batches (model degraded to
                // empty WITHOUT throwing) must NOT be marked compiled — else it's
                // skipped forever with no wiki pages (silent data loss). Only
                // productive-and-not-failed files get compiled_by (below).
                if (outcome.created() + outcome.updated() > 0) {
                    batch.forEach(f -> producedFileIds.add(f.getId()));
                }

                // NB: do NOT persist `batch` entries here — they may be transient
                // segment VARIANTS (same fileId, sliced text, "part k/N" name).
                // Saving one would overwrite the real file's text/name. compiledBy
                // is set on the ORIGINAL files once, after all batches (below).

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
                // Every fileId in this batch is now incomplete — record so it is
                // NOT marked compiled and is re-run next time (resume).
                batch.forEach(f -> failedFileIds.add(f.getId()));
                log.error("[Pipeline:BatchCompile] Batch {}/{} FAILED: {} — " +
                          "previous batches ({} pages) are safe; file(s) {} left INCOMPLETE "
                          + "(compiled_by stays NULL → re-compiled next run)",
                        i + 1, batches.size(), e.getMessage(),
                        totalCreated + totalUpdated,
                        batch.stream().map(KnowledgeFile::getId).distinct().toList());
                // Continue — partial persist: previous batches are already saved
            }
        }

        // Mark compiled_by ONLY on files whose every segment succeeded — this is
        // the completion marker the incremental skip keys on. Files with a failed
        // segment are left NULL (visibly incomplete) so the next compile resumes
        // them; the pages that DID persist are safe and merge on re-run.
        int incomplete = 0;
        int zeroPage = 0;
        for (KnowledgeFile f : newFiles) {
            if (failedFileIds.contains(f.getId())) {
                incomplete++;
                continue; // a segment threw → leave compiled_by NULL → re-compiled
            }
            if (!producedFileIds.contains(f.getId())) {
                zeroPage++;
                continue; // produced 0 pages (soft-empty) → NOT "done", stays re-compilable
            }
            f.markCompiled(lastTier);   // stamps compiled_by + compiled_at
            knowledgeRepository.save(f);
        }
        if (incomplete > 0 || zeroPage > 0) {
            log.warn("[Pipeline:BatchCompile] avatarId={} — {} file(s) INCOMPLETE (segment "
                    + "failure), {} file(s) produced ZERO pages; all left re-compilable on the "
                    + "next trigger instead of marked done.", avatarId, incomplete, zeroPage);
        }

        // Cache invalidation
        cacheInvalidationService.onWikiContentChanged(avatarId, cacheKeepAliveService);

        return new CompileResult(totalCreated, totalUpdated, allTitles,
                lastTier, newFiles.size(), totalCharsCompiled, allFailedPages);
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

    /// Overlap carried into the next compiler WINDOW so a fact spanning a boundary
    /// isn't lost (the windows share one fileId and merge by slug, so the overlap
    /// never double-counts). NB: this is compiler token-limit windowing, distinct
    /// from the picker's chapter SEGMENTING (which uses overlap=0 — see TextWindower).
    static final int WINDOW_OVERLAP = 800;

    /**
     * Legacy-file sweep guard: convert any oversized top-level READY file WITH NO
     * CHILDREN into PERSISTED PENDING_CHUNK children (parent → SEGMENTED), removing
     * it from the compile set. This is the retroactive half of the money-leak fix —
     * a file uploaded before chunking, or one that slipped past the upload-time
     * split, is never compiled whole; it becomes pickable chapters instead. Returns
     * the files that should still compile now (the sub-trigger ones), unchanged.
     */
    private List<KnowledgeFile> segmentOversizedLegacyFiles(String avatarId, List<KnowledgeFile> newFiles) {
        List<KnowledgeFile> compilable = new ArrayList<>();
        for (KnowledgeFile f : newFiles) {
            String text = f.getExtractedText();
            boolean oversizedTopLevel = f.getParentFileId() == null
                    && text != null && text.length() > segmentTriggerChars
                    && !knowledgeRepository.hasChunks(f.getId());
            if (!oversizedTopLevel) {
                compilable.add(f);
                continue;
            }
            List<com.pally.domain.knowledge.Segment> segs = segmentationService.segmentFromText(text);
            if (segs.size() < 2) {          // can't meaningfully split → let it compile
                compilable.add(f);
                continue;
            }
            f.markSegmented();
            knowledgeRepository.save(f);
            for (com.pally.domain.knowledge.Segment seg : segs) {
                int pages = Math.max(1, seg.pageTo() - seg.pageFrom() + 1);
                // INTENTIONAL dedup bypass (same as the upload path): these child
                // chunks are created programmatically, never through the dedup gate.
                // Adjacent chapters tile at overlap=0 so they don't even share
                // boundary text — but the bypass is what makes sibling similarity a
                // non-issue in principle, not the overlap value.
                knowledgeRepository.save(KnowledgeFile.createChunk(
                        f, seg.title(), seg.pageFrom(), seg.pageTo(), pages, seg.text()));
            }
            log.info("[Pipeline:BatchCompile] avatarId={} legacy oversized fileId={} SEGMENTED into "
                    + "{} chunks instead of compiling whole", avatarId, f.getId(), segs.size());
            // dropped from the compile set — nothing compiles until a chunk is picked
        }
        return compilable;
    }

    /**
     * Expands any file whose extracted text exceeds {@code maxChars} into ordered
     * WINDOW variants — each carries the SAME fileId (so provenance / re-upload
     * dedup are preserved) but a bounded text window and a "(part k/N)" name.
     * Files within budget pass through unchanged. Window variants are transient:
     * they are fed to the compiler but never persisted back to the file store.
     *
     * <p>This is compiler token-limit WINDOWING (overlap {@link #WINDOW_OVERLAP}) —
     * distinct from the picker's chapter SEGMENTING; both share {@link TextWindower}.
     */
    static List<KnowledgeFile> windowOversizedFiles(List<KnowledgeFile> files, int maxChars) {
        List<KnowledgeFile> out = new ArrayList<>();
        for (KnowledgeFile f : files) {
            String text = f.getExtractedText();
            if (text == null || text.length() <= maxChars) {
                out.add(f);
                continue;
            }
            List<String> windows = TextWindower.window(text, maxChars, WINDOW_OVERLAP);
            for (int i = 0; i < windows.size(); i++) {
                out.add(KnowledgeFile.reconstitute(
                        f.getId(), f.getAvatarId(), f.getUserId(),
                        f.getFileName() + " (part " + (i + 1) + "/" + windows.size() + ")",
                        f.getStorageKey(), f.getPageCount(), f.getUploadType(),
                        f.getStatus(), f.getCreatedAt(), windows.get(i)));
            }
        }
        return out;
    }

}
