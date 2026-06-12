package com.pally.infrastructure.persistence.challenge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ClassChallengeJpaRepository extends JpaRepository<ClassChallengeJpaEntity, String> {

    List<ClassChallengeJpaEntity> findByClassIdOrderByCreatedAtDesc(String classId);

    /** Challenges whose reveal time has passed but the push hasn't been sent. */
    @Query("SELECT c FROM ClassChallengeJpaEntity c "
            + "WHERE c.notified = false AND c.revealAt <= :now")
    List<ClassChallengeJpaEntity> findDueForReveal(@Param("now") Instant now);
}
