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
    private Instant generatedAt;

    public static ClassBrief create(
            String id, String classId, String moduleId,
            String briefJson, Instant generatedAt) {
        ClassBrief b = new ClassBrief();
        b.id = id;
        b.classId = classId;
        b.moduleId = moduleId;
        b.briefJson = briefJson;
        b.generatedAt = generatedAt;
        return b;
    }
}
