package com.pally.infrastructure.persistence.centre;

import com.pally.domain.centre.ClassBrief;
import com.pally.domain.centre.ClassBriefRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ClassBriefRepositoryAdapter implements ClassBriefRepository {

    private final ClassBriefJpaRepository jpa;

    @Override
    public Optional<ClassBrief> findByClassIdAndModuleId(String classId, String moduleId) {
        return jpa.findByClassIdAndModuleId(classId, moduleId).map(this::toDomain);
    }

    @Override
    public Optional<Instant> findMaxProgressCompletedAt(String classId, String moduleId) {
        return jpa.findMaxProgressCompletedAt(classId, moduleId);
    }

    @Override
    public ClassBrief save(ClassBrief brief) {
        return toDomain(jpa.save(toEntity(brief)));
    }

    @Override
    public List<String> findActiveStudentIds(String classId) {
        return jpa.findActiveStudentIds(classId);
    }

    @Override
    public void deleteByClassIdAndModuleId(String classId, String moduleId) {
        jpa.deleteByClassIdAndModuleId(classId, moduleId);
    }

    // ── Mapping ────────────────────────────────────────────────────────────────

    private ClassBrief toDomain(ClassBriefJpaEntity e) {
        return ClassBrief.create(e.getId(), e.getClassId(), e.getModuleId(),
                e.getBriefJson(), e.getAnonMapJson(), e.getGeneratedAt());
    }

    private ClassBriefJpaEntity toEntity(ClassBrief d) {
        ClassBriefJpaEntity e = new ClassBriefJpaEntity();
        e.setId(d.getId());
        e.setClassId(d.getClassId());
        e.setModuleId(d.getModuleId());
        e.setBriefJson(d.getBriefJson());
        e.setAnonMapJson(d.getAnonMapJson());
        e.setGeneratedAt(d.getGeneratedAt());
        return e;
    }
}
