package com.pally.domain.classroom;

import java.util.List;
import java.util.Optional;

/** Port for persisting classroom sessions. The JPA adapter lives in infrastructure/persistence. */
public interface ClassroomSessionRepository {

    Optional<ClassroomSession> findById(String id);

    /** A live (non-ENDED) session by its join code, if one exists. */
    Optional<ClassroomSession> findLiveByJoinCode(String joinCode);

    /** Every non-ENDED session for this class, newest first — lets a teacher
     *  client recover its session pointer after a page refresh instead of
     *  losing track of a still-running session. */
    List<ClassroomSession> findLiveByClassId(String classId);

    ClassroomSession save(ClassroomSession session);
}
