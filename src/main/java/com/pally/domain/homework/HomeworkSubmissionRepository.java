package com.pally.domain.homework;

import java.util.List;
import java.util.Optional;

/**
 * Port for homework-submission persistence. Traffics only domain types — the
 * JPA adapter lives in {@code infrastructure/persistence/homework}.
 */
public interface HomeworkSubmissionRepository {

    HomeworkSubmission save(HomeworkSubmission submission);

    Optional<HomeworkSubmission> findById(String id);

    /** Teacher inbox: all submissions for a class, newest first. */
    List<HomeworkSubmission> findByClassId(String classId);

    /** Teacher inbox, status-filtered, newest first. */
    List<HomeworkSubmission> findByClassIdAndStatus(String classId, HomeworkSubmissionStatus status);

    /** Student view: my submissions for a class, newest first. */
    List<HomeworkSubmission> findByStudentIdAndClassId(String studentId, String classId);
}
