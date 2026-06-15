package com.pally.infrastructure.persistence.quiz;

import com.pally.domain.centre.QuizQuestionResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Adapter that backs the domain {@link QuizQuestionResultRepository} port
 * using the JPA {@link QuizQuestionResultJpaRepository}.
 */
@Component
@RequiredArgsConstructor
public class QuizQuestionResultRepositoryAdapter implements QuizQuestionResultRepository {

    private final QuizQuestionResultJpaRepository jpa;

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> findStudentActivity(String centreId, String cohortLabel) {
        return jpa.findStudentActivity(centreId, cohortLabel);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> findWeeklyGraspTrend(String centreId, String cohortLabel) {
        return jpa.findWeeklyGraspTrend(centreId, cohortLabel);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> findWeakestTopicsForCentre(String centreId, String cohortLabel, int limit) {
        return jpa.findWeakestTopicsForCentre(centreId, cohortLabel, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> findHeatmapData(String centreId, String cohort) {
        return jpa.findHeatmapData(centreId, cohort);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> findWeeklyGraspForStudent(String studentUserId) {
        return jpa.findWeeklyGraspForStudent(studentUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> findTopicGraspForStudent(String studentUserId) {
        return jpa.findTopicGraspForStudent(studentUserId);
    }
}
