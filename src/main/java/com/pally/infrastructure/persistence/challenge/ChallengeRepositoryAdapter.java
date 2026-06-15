package com.pally.infrastructure.persistence.challenge;

import com.pally.domain.challenge.Challenge;
import com.pally.domain.challenge.ChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JPA adapter implementing the {@link ChallengeRepository} domain port. */
@Component
@RequiredArgsConstructor
public class ChallengeRepositoryAdapter implements ChallengeRepository {

    private final ClassChallengeJpaRepository jpa;

    @Override
    public Challenge save(Challenge c) {
        return toDomain(jpa.save(toEntity(c)));
    }

    @Override
    public Optional<Challenge> findById(String id) {
        return jpa.findById(id).map(ChallengeRepositoryAdapter::toDomain);
    }

    @Override
    public List<Challenge> findByClassIdOrderByCreatedAtDesc(String classId) {
        return jpa.findByClassIdOrderByCreatedAtDesc(classId).stream()
                .map(ChallengeRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<Challenge> findDueForReveal(Instant now) {
        return jpa.findDueForReveal(now).stream()
                .map(ChallengeRepositoryAdapter::toDomain).toList();
    }

    static Challenge toDomain(ClassChallengeJpaEntity e) {
        return new Challenge(e.getId(), e.getClassId(), e.getQuestion(), e.getOptions(),
                e.getAnswer(), e.getRevealAt(), e.getCreatedBy(), e.getCreatedAt(),
                e.isNotified());
    }

    static ClassChallengeJpaEntity toEntity(Challenge c) {
        ClassChallengeJpaEntity e = new ClassChallengeJpaEntity();
        e.setId(c.getId());
        e.setClassId(c.getClassId());
        e.setQuestion(c.getQuestion());
        e.setOptions(c.getOptions());
        e.setAnswer(c.getAnswer());
        e.setRevealAt(c.getRevealAt());
        e.setCreatedBy(c.getCreatedBy());
        e.setCreatedAt(c.getCreatedAt());
        e.setNotified(c.isNotified());
        return e;
    }
}
