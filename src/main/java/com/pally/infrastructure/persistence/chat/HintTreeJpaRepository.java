package com.pally.infrastructure.persistence.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HintTreeJpaRepository extends JpaRepository<HintTreeJpaEntity, String> {
    Optional<HintTreeJpaEntity> findByAvatarIdAndWikiSlug(String avatarId, String wikiSlug);
    List<HintTreeJpaEntity> findByAvatarId(String avatarId);
    void deleteByAvatarIdAndWikiSlug(String avatarId, String wikiSlug);
}
