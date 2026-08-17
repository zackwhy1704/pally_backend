package com.pally.domain.classroom;

import java.util.Optional;

/** Port for persisting classroom sessions. The JPA adapter lives in infrastructure/persistence. */
public interface ClassroomSessionRepository {

    Optional<ClassroomSession> findById(String id);

    /** A live (non-ENDED) session by its join code, if one exists. */
    Optional<ClassroomSession> findLiveByJoinCode(String joinCode);

    ClassroomSession save(ClassroomSession session);
}
