package com.pally.infrastructure.persistence.assignment;

import com.pally.domain.assignment.ContentGapSignalRepository;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA adapter that implements the {@link ContentGapSignalRepository} domain port.
 * Maps the domain call to a {@link ContentGapSignalJpaEntity} and delegates to Spring Data.
 */
@Component
@RequiredArgsConstructor
public class ContentGapSignalRepositoryAdapter implements ContentGapSignalRepository {

    private final ContentGapSignalJpaRepository jpa;

    @Override
    public void recordSignal(String avatarId, String classId, String userId,
                             String queryText, BigDecimal matchedConfidence) {
        ContentGapSignalJpaEntity entity = new ContentGapSignalJpaEntity();
        entity.setId(IdGenerator.newId());
        entity.setClassId(classId);
        entity.setUserId(userId);
        entity.setQueryText(queryText);
        entity.setMatchedConfidence(matchedConfidence);
        entity.setCreatedAt(Instant.now());
        jpa.save(entity);
    }
}
