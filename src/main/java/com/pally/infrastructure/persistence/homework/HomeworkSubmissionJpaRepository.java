package com.pally.infrastructure.persistence.homework;

import com.pally.domain.homework.HomeworkSubmissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HomeworkSubmissionJpaRepository
        extends JpaRepository<HomeworkSubmissionJpaEntity, String> {

    List<HomeworkSubmissionJpaEntity> findByClassIdOrderByCreatedAtDesc(String classId);

    List<HomeworkSubmissionJpaEntity> findByClassIdAndStatusOrderByCreatedAtDesc(
            String classId, HomeworkSubmissionStatus status);

    List<HomeworkSubmissionJpaEntity> findByStudentIdAndClassIdOrderByCreatedAtDesc(
            String studentId, String classId);
}
