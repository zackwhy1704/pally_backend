package com.pally.infrastructure.persistence.homework;

import com.pally.domain.homework.HomeworkSubmissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HomeworkSubmissionJpaRepository
        extends JpaRepository<HomeworkSubmissionJpaEntity, String> {

    /// ACCOUNT DELETION Phase 1 CONTENTFUL DELETE: the submission embeds the student's
    /// OWN work (extracted_text) — content identifies regardless of nulled ids — so it
    /// is deleted, not anonymized. The marking wiki keeps what it already compiled.
    @Modifying
    @Query("DELETE FROM HomeworkSubmissionJpaEntity h WHERE h.studentId = :studentId")
    int deleteByStudentId(@Param("studentId") String studentId);

    List<HomeworkSubmissionJpaEntity> findByClassIdOrderByCreatedAtDesc(String classId);

    List<HomeworkSubmissionJpaEntity> findByClassIdAndStatusOrderByCreatedAtDesc(
            String classId, HomeworkSubmissionStatus status);

    List<HomeworkSubmissionJpaEntity> findByStudentIdAndClassIdOrderByCreatedAtDesc(
            String studentId, String classId);
}
