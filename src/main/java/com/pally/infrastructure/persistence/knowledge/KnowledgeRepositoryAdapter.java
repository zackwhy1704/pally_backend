package com.pally.infrastructure.persistence.knowledge;

import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class KnowledgeRepositoryAdapter implements KnowledgeRepository {

    private final KnowledgeFileJpaRepository fileJpaRepository;

    @Override
    @Transactional
    public KnowledgeFile save(KnowledgeFile kf) {
        return fileJpaRepository.save(KnowledgeFileJpaEntity.fromDomain(kf)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<KnowledgeFile> findById(String id) {
        return fileJpaRepository.findById(id).map(KnowledgeFileJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeFile> findByAvatarId(String avatarId) {
        return fileJpaRepository.findByAvatarId(avatarId).stream()
                .map(KnowledgeFileJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeFile> findByParentFileId(String parentFileId) {
        return fileJpaRepository.findByParentFileId(parentFileId).stream()
                .map(KnowledgeFileJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasChunks(String parentFileId) {
        return fileJpaRepository.existsByParentFileId(parentFileId);
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        fileJpaRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public int countAcceptedUploadsSince(String userId, java.time.Instant since) {
        // "Accepted upload" = a TOP-LEVEL doc (not a chunk) that reached an accepted
        // terminal state. A SEGMENTED parent counts as the one document uploaded;
        // picked child chunks (which become READY) must NOT inflate the doc cap.
        return fileJpaRepository.countAcceptedUploadsSince(
                userId,
                java.util.List.of(KnowledgeFile.Status.READY, KnowledgeFile.Status.SEGMENTED),
                since);
    }

    @Override
    @Transactional(readOnly = true)
    public int countChunkCompilesSince(String userId, java.time.Instant since) {
        return fileJpaRepository.countChunkCompilesSince(userId, since);
    }
}
