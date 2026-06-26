package com.pally.infrastructure.persistence.quiz;

import com.pally.domain.quiz.QuizAnswerKeyRepository;
import com.pally.domain.quiz.QuizQuestion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class QuizAnswerKeyRepositoryAdapter implements QuizAnswerKeyRepository {

    private final QuizAnswerKeyJpaRepository jpa;

    @Override
    public void saveKeys(String avatarId, List<QuizQuestion> questions) {
        if (questions == null || questions.isEmpty()) return;
        Instant now = Instant.now();
        List<QuizAnswerKeyJpaEntity> entities = questions.stream().map(q -> {
            QuizAnswerKeyJpaEntity e = new QuizAnswerKeyJpaEntity();
            e.setQuestionId(q.id());
            e.setAvatarId(avatarId);
            e.setCorrectIndex(q.correctIndex());
            e.setExplanation(q.explanation());
            e.setCreatedAt(now);
            return e;
        }).toList();
        // save() upserts on the @Id (question id), so re-generation overwrites.
        jpa.saveAll(entities);
    }

    @Override
    public Map<String, AnswerKey> findByQuestionIds(Collection<String> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) return Map.of();
        return jpa.findByQuestionIdIn(questionIds).stream()
                .collect(Collectors.toMap(
                        QuizAnswerKeyJpaEntity::getQuestionId,
                        e -> new AnswerKey(e.getCorrectIndex(), e.getExplanation()),
                        (a, b) -> a));
    }

    @Override
    @Transactional
    public int deleteOlderThan(Instant cutoff) {
        return jpa.deleteByCreatedAtBefore(cutoff);
    }
}
