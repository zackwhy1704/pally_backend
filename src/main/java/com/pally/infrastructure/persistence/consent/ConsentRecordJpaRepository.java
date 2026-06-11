package com.pally.infrastructure.persistence.consent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConsentRecordJpaRepository
        extends JpaRepository<ConsentRecordJpaEntity, String> {

    Optional<ConsentRecordJpaEntity> findFirstByUserIdOrderByCreatedAtDesc(String userId);

    Optional<ConsentRecordJpaEntity> findFirstByChildIdAndParentIdOrderByCreatedAtDesc(
            String childId, String parentId);
}
