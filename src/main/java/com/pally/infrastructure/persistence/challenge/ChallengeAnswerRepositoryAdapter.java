package com.pally.infrastructure.persistence.challenge;

import com.pally.domain.challenge.AnswerCount;
import com.pally.domain.challenge.ChallengeAnswer;
import com.pally.domain.challenge.ChallengeAnswerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** JPA adapter implementing the {@link ChallengeAnswerRepository} domain port. */
@Component
@RequiredArgsConstructor
public class ChallengeAnswerRepositoryAdapter implements ChallengeAnswerRepository {

    private final ChallengeAnswerJpaRepository jpa;

    @Override
    public ChallengeAnswer save(ChallengeAnswer a) {
        return toDomain(jpa.save(toEntity(a)));
    }

    @Override
    public boolean existsByChallengeIdAndUserId(String challengeId, String userId) {
        return jpa.existsByChallengeIdAndUserId(challengeId, userId);
    }

    @Override
    public Optional<ChallengeAnswer> findByChallengeIdAndUserId(String challengeId, String userId) {
        return jpa.findByChallengeIdAndUserId(challengeId, userId)
                .map(ChallengeAnswerRepositoryAdapter::toDomain);
    }

    @Override
    public List<ChallengeAnswer> findByChallengeId(String challengeId) {
        return jpa.findByChallengeId(challengeId).stream()
                .map(ChallengeAnswerRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<AnswerCount> distributionByChallenge(String challengeId) {
        return jpa.distributionByChallenge(challengeId).stream()
                .map(row -> new AnswerCount((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    static ChallengeAnswer toDomain(ChallengeAnswerJpaEntity e) {
        return new ChallengeAnswer(e.getId(), e.getChallengeId(), e.getUserId(),
                e.getAnswer(), e.getCreatedAt());
    }

    static ChallengeAnswerJpaEntity toEntity(ChallengeAnswer a) {
        ChallengeAnswerJpaEntity e = new ChallengeAnswerJpaEntity();
        e.setId(a.id());
        e.setChallengeId(a.challengeId());
        e.setUserId(a.userId());
        e.setAnswer(a.answer());
        e.setCreatedAt(a.createdAt());
        return e;
    }
}
