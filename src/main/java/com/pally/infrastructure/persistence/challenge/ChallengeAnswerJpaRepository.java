package com.pally.infrastructure.persistence.challenge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChallengeAnswerJpaRepository extends JpaRepository<ChallengeAnswerJpaEntity, String> {

    Optional<ChallengeAnswerJpaEntity> findByChallengeIdAndUserId(String challengeId, String userId);

    boolean existsByChallengeIdAndUserId(String challengeId, String userId);

    List<ChallengeAnswerJpaEntity> findByChallengeId(String challengeId);

    /**
     * Answer distribution: [answer, count]. NO user identifiers are selected.
     */
    @Query("SELECT a.answer AS answer, COUNT(a) AS cnt "
            + "FROM ChallengeAnswerJpaEntity a WHERE a.challengeId = :challengeId "
            + "GROUP BY a.answer ORDER BY COUNT(a) DESC")
    List<Object[]> distributionByChallenge(@Param("challengeId") String challengeId);
}
