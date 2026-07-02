package com.pally.infrastructure.persistence.quiz;

import com.pally.domain.weakness.WeaknessSignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps quiz_question_results rows into the domain's {@link TopicMastery} signal
 * for the WEAKNESS_PROFILE head. Reuses the existing per-avatar mastery query
 * ({@code findAllTopicMasteryByAvatar} → [topic_slug, ratio, attempts]).
 */
@Component
@RequiredArgsConstructor
public class WeaknessSignalRepositoryAdapter implements WeaknessSignalRepository {

    private final QuizQuestionResultJpaRepository jpa;

    @Override
    public List<TopicMastery> findTopicMastery(String userId, String sourceAvatarId) {
        return jpa.findAllTopicMasteryByAvatar(userId, sourceAvatarId).stream()
                .map(row -> new TopicMastery(
                        (String) row[0],
                        row[1] == null ? 0.0 : ((Number) row[1]).doubleValue(),
                        row[2] == null ? 0 : ((Number) row[2]).intValue()))
                .toList();
    }
}
