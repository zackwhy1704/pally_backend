package com.pally.domain.knowledge.usecase;

import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.infrastructure.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Use case: delete a knowledge file from storage and the database.
 */
@Service
@RequiredArgsConstructor
public class DeleteFileUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeleteFileUseCase.class);

    private final KnowledgeRepository knowledgeRepository;
    private final StorageService storageService;

    public void execute(String fileId, String userId) {
        knowledgeRepository.findById(fileId)
                .filter(f -> f.getUserId().equals(userId))
                .ifPresentOrElse(f -> {
                    storageService.delete(f.getStorageKey());
                    knowledgeRepository.deleteById(fileId);
                    log.info("Deleted knowledge file fileId={}", fileId);
                }, () -> log.warn("Knowledge file not found or access denied fileId={}", fileId));
    }
}
