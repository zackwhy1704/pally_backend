package com.pally.infrastructure.persistence.chat;

import com.pally.domain.chat.HintTreeRepository;
import com.pally.domain.chat.SocraticHintTree;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class HintTreeRepositoryAdapter implements HintTreeRepository {

    private final HintTreeJpaRepository jpaRepository;

    @Override
    public void save(SocraticHintTree tree) {
        jpaRepository.save(HintTreeJpaEntity.fromDomain(tree));
    }

    @Override
    public Optional<SocraticHintTree> findByAvatarIdAndSlug(String avatarId, String wikiSlug) {
        return jpaRepository.findByAvatarIdAndWikiSlug(avatarId, wikiSlug)
                .map(HintTreeJpaEntity::toDomain);
    }

    @Override
    public List<SocraticHintTree> findByAvatarId(String avatarId) {
        return jpaRepository.findByAvatarId(avatarId).stream()
                .map(HintTreeJpaEntity::toDomain)
                .toList();
    }

    @Override
    public void deleteByAvatarIdAndSlug(String avatarId, String wikiSlug) {
        jpaRepository.deleteByAvatarIdAndWikiSlug(avatarId, wikiSlug);
    }
}
