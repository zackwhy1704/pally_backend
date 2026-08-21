package com.pally.infrastructure.persistence.metrics;

import com.pally.infrastructure.persistence.avatar.AvatarJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read-only aggregate queries for cohort metrics. Native SQL because these are
 * set-level aggregates over joins that JPQL expresses poorly; every query here is a
 * SELECT — this repository performs no writes.
 *
 * <p><b>Day bucketing is Asia/Singapore, always.</b> Every "calendar day" boundary
 * uses {@code AT TIME ZONE 'Asia/Singapore'}, matching the project-wide convention
 * ({@code PallyTime.SGT}). Bucketing in UTC would misclassify evening study sessions:
 * a student working at 21:00 SGT sits at 13:00 UTC the SAME day, but one at 08:00 SGT
 * is 00:00 UTC — so a UTC bucket splits or merges days wrongly exactly when students
 * actually study, silently corrupting "returned the next day".
 */
public interface CohortMetricsJpaRepository extends JpaRepository<AvatarJpaEntity, String> {

    String SGT = "Asia/Singapore";

    /** Avatars matching the cohort filter that have at least one recorded action. */
    @Query(value = """
            SELECT COUNT(DISTINCT a.id) FROM avatars a
            WHERE (:level IS NULL OR a.grade_level = :level)
              AND (:subject IS NULL OR a.subject = :subject)
              AND EXISTS (
                SELECT 1 FROM module_progress mp
                JOIN learning_module lm ON lm.id = mp.module_id
                WHERE lm.avatar_id = a.id AND mp.completed_at IS NOT NULL)
            """, nativeQuery = true)
    int countActiveAvatars(@Param("level") String level, @Param("subject") String subject);

    /** Avatars holding content but with ZERO recorded activity. Reported, never counted. */
    @Query(value = """
            SELECT COUNT(DISTINCT a.id) FROM avatars a
            WHERE (:level IS NULL OR a.grade_level = :level)
              AND (:subject IS NULL OR a.subject = :subject)
              AND (a.wiki_page_count > 0
                   OR EXISTS (SELECT 1 FROM flashcards f WHERE f.avatar_id = a.id))
              AND NOT EXISTS (
                SELECT 1 FROM module_progress mp
                JOIN learning_module lm ON lm.id = mp.module_id
                WHERE lm.avatar_id = a.id AND mp.completed_at IS NOT NULL)
            """, nativeQuery = true)
    int countDormantAvatars(@Param("level") String level, @Param("subject") String subject);

    /** Every avatar in the cohort — the activation denominator. */
    @Query(value = """
            SELECT COUNT(*) FROM avatars a
            WHERE (:level IS NULL OR a.grade_level = :level)
              AND (:subject IS NULL OR a.subject = :subject)
            """, nativeQuery = true)
    int countAllAvatars(@Param("level") String level, @Param("subject") String subject);

    /** Active avatars whose activity spans MORE THAN ONE Asia/Singapore calendar day. */
    @Query(value = """
            SELECT COUNT(*) FROM (
              SELECT lm.avatar_id
              FROM module_progress mp
              JOIN learning_module lm ON lm.id = mp.module_id
              JOIN avatars a ON a.id = lm.avatar_id
              WHERE mp.completed_at IS NOT NULL
                AND (:level IS NULL OR a.grade_level = :level)
                AND (:subject IS NULL OR a.subject = :subject)
              GROUP BY lm.avatar_id
              HAVING COUNT(DISTINCT (mp.completed_at AT TIME ZONE 'Asia/Singapore')::date) > 1
            ) t
            """, nativeQuery = true)
    int countReturnedOnLaterDay(@Param("level") String level, @Param("subject") String subject);

    /** Distinct cards that have any review row — the repeat-review denominator. */
    @Query(value = """
            SELECT COUNT(DISTINCT fr.flashcard_id) FROM flashcard_review fr
            JOIN avatars a ON a.id = fr.avatar_id
            WHERE (:level IS NULL OR a.grade_level = :level)
              AND (:subject IS NULL OR a.subject = :subject)
            """, nativeQuery = true)
    int countReviewedCards(@Param("level") String level, @Param("subject") String subject);

    /** Cards reviewed more than once. */
    @Query(value = """
            SELECT COUNT(*) FROM (
              SELECT fr.flashcard_id FROM flashcard_review fr
              JOIN avatars a ON a.id = fr.avatar_id
              WHERE (:level IS NULL OR a.grade_level = :level)
                AND (:subject IS NULL OR a.subject = :subject)
              GROUP BY fr.flashcard_id HAVING COUNT(*) > 1
            ) t
            """, nativeQuery = true)
    int countRepeatReviewedCards(@Param("level") String level, @Param("subject") String subject);

    /**
     * Of reviews that found the card already in a successful streak
     * ({@code prev_repetitions > 0} — a GENUINE repeat recall, not a post-lapse
     * restart), how many were recalled correctly? SM-2 quality >= 3 is a successful
     * recall; HARD (q=2) is a lapse.
     */
    @Query(value = """
            SELECT COUNT(*) FROM flashcard_review fr
            JOIN avatars a ON a.id = fr.avatar_id
            WHERE fr.prev_repetitions > 0
              AND (:level IS NULL OR a.grade_level = :level)
              AND (:subject IS NULL OR a.subject = :subject)
            """, nativeQuery = true)
    int countRepeatRecallAttempts(@Param("level") String level, @Param("subject") String subject);

    @Query(value = """
            SELECT COUNT(*) FROM flashcard_review fr
            JOIN avatars a ON a.id = fr.avatar_id
            WHERE fr.prev_repetitions > 0 AND fr.quality >= 3
              AND (:level IS NULL OR a.grade_level = :level)
              AND (:subject IS NULL OR a.subject = :subject)
            """, nativeQuery = true)
    int countRepeatRecallCorrect(@Param("level") String level, @Param("subject") String subject);

    /**
     * Median days between an avatar's FIRST and SECOND distinct active day
     * (Asia/Singapore). Null when no avatar has a second day.
     */
    @Query(value = """
            SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY gap) FROM (
              SELECT (d2 - d1) AS gap FROM (
                SELECT lm.avatar_id,
                       MIN(day) AS d1,
                       MIN(day) FILTER (WHERE day > MIN(day) OVER (PARTITION BY lm.avatar_id)) AS d2
                FROM (
                  SELECT lm2.avatar_id AS aid,
                         (mp.completed_at AT TIME ZONE 'Asia/Singapore')::date AS day
                  FROM module_progress mp
                  JOIN learning_module lm2 ON lm2.id = mp.module_id
                  WHERE mp.completed_at IS NOT NULL
                  GROUP BY lm2.avatar_id, 2
                ) days
                JOIN learning_module lm ON lm.avatar_id = days.aid
                JOIN avatars a ON a.id = lm.avatar_id
                WHERE (:level IS NULL OR a.grade_level = :level)
                  AND (:subject IS NULL OR a.subject = :subject)
                GROUP BY lm.avatar_id
              ) g WHERE d2 IS NOT NULL
            ) x
            """, nativeQuery = true)
    Double medianDaysToSecondSession(@Param("level") String level, @Param("subject") String subject);

    /** How many avatars actually have a second active day — the median's n. */
    @Query(value = """
            SELECT COUNT(*) FROM (
              SELECT lm.avatar_id
              FROM module_progress mp
              JOIN learning_module lm ON lm.id = mp.module_id
              JOIN avatars a ON a.id = lm.avatar_id
              WHERE mp.completed_at IS NOT NULL
                AND (:level IS NULL OR a.grade_level = :level)
                AND (:subject IS NULL OR a.subject = :subject)
              GROUP BY lm.avatar_id
              HAVING COUNT(DISTINCT (mp.completed_at AT TIME ZONE 'Asia/Singapore')::date) > 1
            ) t
            """, nativeQuery = true)
    int countAvatarsWithSecondDay(@Param("level") String level, @Param("subject") String subject);
}
