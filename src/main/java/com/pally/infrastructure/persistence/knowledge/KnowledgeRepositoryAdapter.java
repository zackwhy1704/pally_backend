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
    @Transactional
    public void deleteById(String id) {
        fileJpaRepository.deleteById(id);
    }
}
