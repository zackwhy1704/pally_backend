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

    @Qualifier(AiTaskExecutorConfig.AI_TASK_EXECUTOR)
    private final ThreadPoolExecutor aiTaskExecutor;

    public record CompileResult(
            int pagesCreated,
            int pagesUpdated,
            List<String> pageTitles
    ) {}

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

        List<KnowledgeFile> readyFiles = knowledgeRepository.findByAvatarId(avatarId).stream()
                .filter(f -> f.getStatus() == KnowledgeFile.Status.READY)
                .toList();

        if (readyFiles.isEmpty()) {
            log.warn("No READY files found for avatarId={}, skipping wiki compile", avatarId);
            return new CompileResult(0, 0, List.of());
        }

        log.info("Compiling wiki for avatarId={} from {} files", avatarId, readyFiles.size());

        List<WikiPage> existingPages = wikiRepository.findByAvatarId(avatarId);

        List<WikiCompilerPort.WikiPageDraft> drafts;
        try {
            drafts = wikiCompiler.compile(avatar, readyFiles, existingPages);
        } catch (Exception e) {
            throw new WikiCompileException("Wiki compilation failed for avatar " + avatarId, e);
        }

        // The Claude compile above is the slow part (often 30-60s). The
        // persistence below runs in a short @Transactional block in a
        // dedicated service so a DB transaction is never held open
        // across an AI call.
        WikiPagePersistenceService.PersistOutcome outcome =
                persistenceService.persistDrafts(avatar, drafts);

        log.info("Wiki compiled for avatarId={} created={} updated={}",
                avatarId, outcome.created(), outcome.updated());

        // Invalidate Block 3 cache so next request picks up the new content.
        // Best-effort cache work stays outside the persistence transaction.
        cacheInvalidationService.onWikiContentChanged(avatarId, cacheKeepAliveService);

        return new CompileResult(
                outcome.created(), outcome.updated(), outcome.pageTitles());
    }

}
