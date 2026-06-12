package com.pally.infrastructure.persistence.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContentReviewRequestJpaRepository
        extends JpaRepository<ContentReviewRequestJpaEntity, String> {

    Optional<ContentReviewRequestJpaEntity> findByToken(String token);

    List<ContentReviewRequestJpaEntity> findByWikiPageIdOrderByCreatedAtDesc(String wikiPageId);

    List<ContentReviewRequestJpaEntity> findByWikiPageIdAndStatus(String wikiPageId, String status);

    long countByWikiPageIdAndStatus(String wikiPageId, String status);
}
