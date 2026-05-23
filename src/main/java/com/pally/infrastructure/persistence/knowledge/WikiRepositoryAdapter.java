package com.pally.infrastructure.persistence.knowledge;

import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class WikiRepositoryAdapter implements WikiRepository {

    private final WikiPageJpaRepository wikiJpaRepository;

    @Override
    @Transactional
    public WikiPage save(WikiPage wikiPage) {
        return wikiJpaRepository.save(WikiPageJpaEntity.fromDomain(wikiPage)).toDomain();
    }

    @Override
    @Transactional
    public List<WikiPage> saveAll(List<WikiPage> pages) {
        return wikiJpaRepository.saveAll(pages.stream().map(WikiPageJpaEntity::fromDomain).toList())
                .stream().map(WikiPageJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WikiPage> findByAvatarIdAndSlug(String avatarId, String slug) {
        return wikiJpaRepository.findByAvatarIdAndSlug(avatarId, slug).map(WikiPageJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WikiPage> findByAvatarId(String avatarId) {
        return wikiJpaRepository.findByAvatarId(avatarId).stream()
                .map(WikiPageJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public int countByAvatarId(String avatarId) {
        return wikiJpaRepository.countByAvatarId(avatarId);
    }

    @Override
    @Transactional
    public void deleteByAvatarId(String avatarId) {
        wikiJpaRepository.deleteByAvatarId(avatarId);
    }
}
