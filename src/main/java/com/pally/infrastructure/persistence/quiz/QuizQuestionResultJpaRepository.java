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

    /// Returns [topicSlug, correctRatio, attempts] for every topic the user
    /// has any history with under [avatarId]. No HAVING filter — even single
    /// attempts surface so the brain map can colour them as low-confidence.
    @Query(value = """
            SELECT topic_slug,
                   AVG(CASE WHEN was_correct THEN 1.0 ELSE 0.0 END) AS ratio,
                   COUNT(*) AS attempts
            FROM quiz_question_results
            WHERE user_id = :userId
              AND avatar_id = :avatarId
              AND topic_slug IS NOT NULL
            GROUP BY topic_slug
            """, nativeQuery = true)
    List<Object[]> findAllTopicMasteryByAvatar(
            @Param("userId") String userId,
            @Param("avatarId") String avatarId);

    /// Distinct calendar days on which the user has at least one answer
    /// recorded. Drives "quizzes taken" on the progress dashboard.
    @Query("""
            SELECT COUNT(DISTINCT CAST(r.createdAt AS date))
            FROM QuizQuestionResultJpaEntity r
            WHERE r.userId = :userId
            """)
    long countDistinctDaysByUserId(@Param("userId") String userId);

    /// Overall fraction of correct answers across all attempts. Returns 0.0
    /// when the user has no quiz history.
    @Query("""
            SELECT COALESCE(AVG(CASE WHEN r.wasCorrect THEN 1.0 ELSE 0.0 END), 0.0)
            FROM QuizQuestionResultJpaEntity r
            WHERE r.userId = :userId
            """)
    double averageAccuracyByUserId(@Param("userId") String userId);

    /// Cohort-scoped aggregate for the centre admin dashboard. One SQL
    /// trip returns the weakest topics across every student in a centre
    /// (optionally filtered to a cohort label), with the number of
    /// students affected per topic — replaces the per-student loop that
    /// did N round-trips for an N-student cohort.
    /// Cohort filter is "match-or-skip": pass NULL to span the whole centre.
    @Query(value = """
            SELECT r.topic_slug                                       AS topic,
                   AVG(CASE WHEN r.was_correct THEN 1.0 ELSE 0.0 END) AS ratio,
                   COUNT(DISTINCT r.user_id)                          AS students_affected,
                   COUNT(*)                                           AS attempts
            FROM quiz_question_results r
            JOIN users u ON u.id = r.user_id
            WHERE u.centre_id = :centreId
              AND (CAST(:cohort AS varchar) IS NULL OR u.cohort_label = :cohort)
              AND r.topic_slug IS NOT NULL
            GROUP BY r.topic_slug
            HAVING COUNT(*) >= 3
            ORDER BY ratio ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findWeakestTopicsForCentre(
            @Param("centreId") String centreId,
            @Param("cohort") String cohortLabel,
            @Param("limit") int limit);

    /// Total correct answers across all the user's quizzes — drives the
    /// QUIZ_CORRECT_50 / 250 achievements.
    @Query("""
            SELECT COUNT(r)
            FROM QuizQuestionResultJpaEntity r
            WHERE r.userId = :userId AND r.wasCorrect = TRUE
            """)
    long countCorrectByUserId(@Param("userId") String userId);

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
