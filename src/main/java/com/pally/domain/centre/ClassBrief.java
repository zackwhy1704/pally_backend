package com.pally.domain.centre;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A cached AI-generated action brief for a centre teacher.
 * Scoped to one class, optionally narrowed to one module.
 */
@Getter
@Setter
@NoArgsConstructor
public class ClassBrief {

    private String id;
    private String classId;
    private String moduleId; // null = whole-class scope
    private String briefJson;
    /** JSON map of userId → "Student #N" as captured at generation time. */
    private String anonMapJson;
    private Instant generatedAt;

    public static ClassBrief create(
            String id, String classId, String moduleId,
            String briefJson, String anonMapJson, Instant generatedAt) {
        ClassBrief b = new ClassBrief();
        b.id = id;
        b.classId = classId;
        b.moduleId = moduleId;
        b.briefJson = briefJson;
        b.anonMapJson = anonMapJson;
        b.generatedAt = generatedAt;
        return b;
    }
}
