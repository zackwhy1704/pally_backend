package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.infrastructure.ai.CacheInvalidationService;
import com.pally.infrastructure.ai.CacheKeepAliveService;
import com.pally.infrastructure.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

/**
 * Deletes a knowledge file from storage and DB, then asynchronously recompiles
 * the wiki so the brain immediately reflects the removal.
 *
 * <p>Without the recompile, wiki pages synthesised from the deleted file
 * persist, and the tutor still answers from material the student intended
 * to remove.
 */
@Service
public class DeleteFileUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeleteFileUseCase.class);

    private final KnowledgeRepository knowledgeRepository;
    private final StorageService storageService;
    private final AvatarRepository avatarRepository;
    private final CompileWikiUseCase compileWikiUseCase;
    private final CacheInvalidationService cacheInvalidationService;
    private final CacheKeepAliveService cacheKeepAliveService;
    private final Executor aiTaskExecutor;

    public DeleteFileUseCase(
            KnowledgeRepository knowledgeRepository,
            StorageService storageService,
            AvatarRepository avatarRepository,
            CompileWikiUseCase compileWikiUseCase,
            CacheInvalidationService cacheInvalidationService,
            CacheKeepAliveService cacheKeepAliveService,
            @Qualifier("aiTaskExecutor") Executor aiTaskExecutor) {
        this.knowledgeRepository = knowledgeRepository;
        this.storageService = storageService;
        this.avatarRepository = avatarRepository;
        this.compileWikiUseCase = compileWikiUseCase;
        this.cacheInvalidationService = cacheInvalidationService;
        this.cacheKeepAliveService = cacheKeepAliveService;
        this.aiTaskExecutor = aiTaskExecutor;
    }

    public void execute(String fileId, String userId) {
        knowledgeRepository.findById(fileId)
                .filter(f -> f.getUserId().equals(userId))
                .ifPresentOrElse(f -> {
                    String avatarId = f.getAvatarId();
                    storageService.delete(f.getStorageKey());
                    knowledgeRepository.deleteById(fileId);
                    log.info("[Delete] Deleted knowledge file fileId={} avatarId={}", fileId, avatarId);

                    // Recompile wiki in the background so pages derived from
                    // the deleted file are replaced or removed. Without this,
                    // the brain retains stale content the student deleted.
                    aiTaskExecutor.execute(() -> {
                        try {
                            CompileWikiUseCase.CompileResult result =
                                    compileWikiUseCase.execute(avatarId);
                            int total = result.pagesCreated() + result.pagesUpdated();
                            avatarRepository.findById(avatarId).ifPresent(avatar -> {
                                avatar.setWikiPageCount(total);
                                avatarRepository.save(avatar);
                            });
                            cacheInvalidationService.onWikiContentChanged(avatarId, cacheKeepAliveService);
                            log.info("[Delete] Post-delete recompile done avatarId={} pages={}",
                                    avatarId, total);
                        } catch (Exception e) {
                            log.warn("[Delete] Post-delete recompile failed avatarId={}: {}",
                                    avatarId, e.getMessage());
                        }
                    });
                }, () -> log.warn("[Delete] File not found or access denied fileId={}", fileId));
    }
}
