package com.pally.domain.centre;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ClassBriefRepository {

    Optional<ClassBrief> findByClassIdAndModuleId(String classId, String moduleId);

    /** Returns the most recent module_progress.completed_at for this class+module scope. */
    Optional<Instant> findMaxProgressCompletedAt(String classId, String moduleId);

    /** Returns user IDs of all ACTIVE students enrolled in this class. */
    List<String> findActiveStudentIds(String classId);

    ClassBrief save(ClassBrief brief);

    void deleteByClassIdAndModuleId(String classId, String moduleId);
}
