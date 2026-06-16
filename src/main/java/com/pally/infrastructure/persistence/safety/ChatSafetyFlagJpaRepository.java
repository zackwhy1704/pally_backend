package com.pally.infrastructure.persistence.safety;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ChatSafetyFlagJpaRepository
        extends JpaRepository<ChatSafetyFlagJpaEntity, String> {

    List<ChatSafetyFlagJpaEntity> findByChildUserIdOrderByCreatedAtDesc(String childUserId);

    List<ChatSafetyFlagJpaEntity> findByChildUserIdAndResolvedFalseOrderByCreatedAtDesc(
            String childUserId);

    long countByChildUserIdAndCreatedAtAfter(String childUserId, Instant since);

    List<ChatSafetyFlagJpaEntity> findByChildUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
            String childUserId, Instant since);
}
