package com.pally.infrastructure.persistence.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuizQuestionResultJpaRepository
        extends JpaRepository<QuizQuestionResultJpaEntity, String> {

    /// Returns rows of [topicSlug, correctRatio, totalAttempts] for the user's
    /// weakest topics, weakest first. Excludes topics with fewer than 3 attempts
    /// to avoid noise.
    @Query(value = """
            SELECT topic_slug,
                   AVG(CASE WHEN was_correct THEN 1.0 ELSE 0.0 END) AS ratio,
                   COUNT(*) AS attempts
            FROM quiz_question_results
            WHERE user_id = :userId
              AND topic_slug IS NOT NULL
            GROUP BY topic_slug
            HAVING COUNT(*) >= 3
            ORDER BY ratio ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findWeakestTopics(@Param("userId") String userId,
                                     @Param("limit") int limit);

    /// Same as [findWeakestTopics] but scoped to a single avatar.
    @Query(value = """
            SELECT topic_slug,
                   AVG(CASE WHEN was_correct THEN 1.0 ELSE 0.0 END) AS ratio,
                   COUNT(*) AS attempts
            FROM quiz_question_results
            WHERE user_id = :userId
              AND avatar_id = :avatarId
              AND topic_slug IS NOT NULL
            GROUP BY topic_slug
            HAVING COUNT(*) >= 3
            ORDER BY ratio ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findWeakestTopicsByAvatar(@Param("userId") String userId,
                                             @Param("avatarId") String avatarId,
                                             @Param("limit") int limit);

    /// Did the user already take a quiz for this avatar today (UTC)?
    @Query(value = """
            SELECT EXISTS (
              SELECT 1 FROM quiz_question_results
              WHERE user_id = :userId
                AND avatar_id = :avatarId
                AND created_at::date = CURRENT_DATE
            )
            """, nativeQuery = true)
    Boolean takenToday(@Param("userId") String userId,
                       @Param("avatarId") String avatarId);
}
