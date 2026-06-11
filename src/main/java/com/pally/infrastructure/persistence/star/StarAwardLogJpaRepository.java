package com.pally.infrastructure.persistence.star;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface StarAwardLogJpaRepository extends JpaRepository<StarAwardLogJpaEntity, String> {

    List<StarAwardLogJpaEntity> findByChildIdOrderByAwardedAtDesc(String childId);

    @Query("""
            SELECT COALESCE(SUM(s.amount), 0)
            FROM StarAwardLogJpaEntity s
            WHERE s.parentId = :parentId
              AND s.awardedAt >= :since
            """)
    int sumAmountByParentIdSince(@Param("parentId") String parentId,
                                 @Param("since") Instant since);
}
