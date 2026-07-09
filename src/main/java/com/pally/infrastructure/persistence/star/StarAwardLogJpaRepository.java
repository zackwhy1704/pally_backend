package com.pally.infrastructure.persistence.star;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface StarAwardLogJpaRepository extends JpaRepository<StarAwardLogJpaEntity, String> {

    List<StarAwardLogJpaEntity> findByChildIdOrderByAwardedAtDesc(String childId);

    /// Account deletion: clear rows where the user is EITHER side. These FKs to users
    /// have no ON DELETE, so leaving them would abort the whole delete transaction.
    @Modifying
    @Query("DELETE FROM StarAwardLogJpaEntity s WHERE s.parentId = :u OR s.childId = :u")
    void deleteByParticipant(@Param("u") String userId);

    @Query("""
            SELECT COALESCE(SUM(s.amount), 0)
            FROM StarAwardLogJpaEntity s
            WHERE s.parentId = :parentId
              AND s.awardedAt >= :since
            """)
    int sumAmountByParentIdSince(@Param("parentId") String parentId,
                                 @Param("since") Instant since);
}
