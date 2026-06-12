package com.pally.infrastructure.persistence.module;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MuddiestVoteJpaRepository extends JpaRepository<MuddiestVoteJpaEntity, String> {

    Optional<MuddiestVoteJpaEntity> findByModuleIdAndUserId(String moduleId, String userId);

    long countByModuleId(String moduleId);

    /**
     * Aggregate vote counts per concept for a module. Returns rows of
     * [conceptId, count]. NO user identifiers are selected.
     */
    @Query("SELECT v.conceptId AS conceptId, COUNT(v) AS cnt "
            + "FROM MuddiestVoteJpaEntity v WHERE v.moduleId = :moduleId "
            + "GROUP BY v.conceptId ORDER BY COUNT(v) DESC")
    List<Object[]> countByConcept(@Param("moduleId") String moduleId);
}
