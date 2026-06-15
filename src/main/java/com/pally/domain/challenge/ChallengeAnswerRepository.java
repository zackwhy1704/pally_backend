package com.pally.domain.challenge;

import java.util.List;
import java.util.Optional;

/** Domain port for challenge-answer persistence. */
public interface ChallengeAnswerRepository {

    ChallengeAnswer save(ChallengeAnswer answer);

    boolean existsByChallengeIdAndUserId(String challengeId, String userId);

    Optional<ChallengeAnswer> findByChallengeIdAndUserId(String challengeId, String userId);

    List<ChallengeAnswer> findByChallengeId(String challengeId);

    /** Answer distribution (answer + count), no user identifiers, busiest first. */
    List<AnswerCount> distributionByChallenge(String challengeId);
}
