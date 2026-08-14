package com.pally.infrastructure.persistence.learning;

import com.pally.domain.learning.LearningEvent;
import com.pally.domain.learning.LearningEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class LearningEventRepositoryAdapter implements LearningEventRepository {

    private final LearningEventJpaRepository jpa;

    @Override
    @Transactional
    public LearningEvent save(LearningEvent event) {
        LearningEventJpaEntity e = new LearningEventJpaEntity();
        e.setId(event.id());
        e.setUserId(event.userId());
        e.setAvatarId(event.avatarId());
        e.setSource(event.source());
        e.setProvenance(event.provenance());
        e.setTopicSlug(event.topicSlug());
        e.setScore(event.score());
        e.setOccurredAt(event.occurredAt());
        e.setSourceRowId(event.sourceRowId());
        jpa.save(e);
        return event;
    }
}
