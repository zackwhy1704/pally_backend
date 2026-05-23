package com.pally.infrastructure.persistence.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface QuizAnswerRecordJpaRepository extends JpaRepository<QuizAnswerRecordJpaEntity, String> {

    List<QuizAnswerRecordJpaEntity> findByAvatarIdAndCorrectFalseOrderByCreatedAtDesc(
            String avatarId);

    @Query("SELECT r.topicSlug, COUNT(r) as errorCount FROM QuizAnswerRecordJpaEntity r " +
           "WHERE r.avatarId = :avatarId AND r.correct = false AND r.topicSlug IS NOT NULL " +
           "GROUP BY r.topicSlug ORDER BY errorCount DESC")
    List<Object[]> findTopErrorTopics(String avatarId);
}
