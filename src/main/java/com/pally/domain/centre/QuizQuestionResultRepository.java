package com.pally.domain.centre;

import java.util.List;

/**
 * Domain port for quiz-question-result queries used by centre analytics.
 * The JPA adapter lives in {@code infrastructure/persistence/quiz}.
 *
 * <p>All methods return {@code Object[]} rows to preserve backward
 * compatibility with the native SQL projections already defined on
 * {@code QuizQuestionResultJpaRepository}. Column order is documented
 * per method.
 *
 * <p>No persistence writes are performed by analytics — every method
 * is read-only.
 */
public interface QuizQuestionResultRepository {

    /**
     * Per-student overall grasp + recent activity for at-risk detection.
     *
     * <p>Row columns: [userId, displayName, cohortLabel, grasp14d, attempts14d, lastActive]
     * LEFT JOIN ensures students with no recent attempts still appear (lastActive = null).
     *
     * @param centreId    the organisation ID
     * @param cohortLabel null to span the whole centre; non-null to filter to one cohort
     */
    List<Object[]> findStudentActivity(String centreId, String cohortLabel);

    /**
     * Weekly average grasp for the whole centre (or one cohort), up to 12 weeks.
     *
     * <p>Row columns: [week (DATE_TRUNC result), grasp]
     *
     * @param centreId    the organisation ID
     * @param cohortLabel null for whole centre; non-null to filter to one cohort
     */
    List<Object[]> findWeeklyGraspTrend(String centreId, String cohortLabel);

    /**
     * Weakest topics across the centre (or cohort), sorted weakest-first.
     *
     * <p>Row columns: [topicSlug, ratio, studentsAffected, attempts]
     *
     * @param centreId    the organisation ID
     * @param cohortLabel null for whole centre; non-null to filter to one cohort
     * @param limit       maximum rows returned
     */
    List<Object[]> findWeakestTopicsForCentre(String centreId, String cohortLabel, int limit);

    /**
     * Per-user per-topic grasp for building the cohort heatmap.
     *
     * <p>Row columns: [userId, topicSlug, grasp, n]
     *
     * @param centreId the organisation ID
     * @param cohort   the cohort label (URL-decoded)
     */
    List<Object[]> findHeatmapData(String centreId, String cohort);

    /**
     * Weekly grasp trend for a single student, up to 12 weeks.
     *
     * <p>Row columns: [week (DATE_TRUNC result), grasp]
     *
     * @param studentUserId the student's user ID
     */
    List<Object[]> findWeeklyGraspForStudent(String studentUserId);

    /**
     * Per-topic grasp for a single student (for topic-bar section of student detail).
     *
     * <p>Row columns: [topicSlug, grasp, n]
     *
     * @param studentUserId the student's user ID
     */
    List<Object[]> findTopicGraspForStudent(String studentUserId);
}
