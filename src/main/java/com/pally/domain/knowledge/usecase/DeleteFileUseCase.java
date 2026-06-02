package com.pally.domain.knowledge.usecase;

import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.infrastructure.ai.WikiRecompileScheduler;
import com.pally.infrastructure.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deletes a knowledge file from storage and DB, then requests a debounced wiki
 * recompile so the brain reflects the removal. Orphan pages (derived from the
 * deleted file) are pruned during the next compile run.
 */
@Service
public class DeleteFileUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeleteFileUseCase.class);

    private final KnowledgeRepository knowledgeRepository;
    private final StorageService storageService;
    private final WikiRecompileScheduler recompileScheduler;

    public DeleteFileUseCase(
            KnowledgeRepository knowledgeRepository,
            StorageService storageService,
            WikiRecompileScheduler recompileScheduler) {
        this.knowledgeRepository = knowledgeRepository;
        this.storageService      = storageService;
        this.recompileScheduler  = recompileScheduler;
    }

    public void execute(String fileId, String userId) {
        knowledgeRepository.findById(fileId)
                .filter(f -> f.getUserId().equals(userId))
                .ifPresentOrElse(f -> {
                    String avatarId = f.getAvatarId();
                    storageService.delete(f.getStorageKey());
                    knowledgeRepository.deleteById(fileId);
                    log.info("[Delete] Deleted knowledge file fileId={} avatarId={}", fileId, avatarId);

                    // Debounced recompile: coalesces rapid deletes and marks
                    // PENDING_RECOMPILE immediately. CompileWikiUseCase.execute()
                    // archives pages derived from the deleted file via archiveOrphanPages().
                    recompileScheduler.requestRecompile(avatarId);
                    log.info("[Delete] Post-delete recompile requested via scheduler avatarId={}", avatarId);
                }, () -> log.warn("[Delete] File not found or access denied fileId={}", fileId));
    }
}
