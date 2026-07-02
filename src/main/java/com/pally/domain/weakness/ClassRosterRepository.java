package com.pally.domain.weakness;

import java.util.List;

/** Domain port: the active student roster of a class (for the teacher weakness view). */
public interface ClassRosterRepository {
    List<String> activeStudentIds(String classId);
}
