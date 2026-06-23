package com.pally.infrastructure.persistence.centre;

import com.pally.domain.centre.ClassReportStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * JPA adapter satisfying the {@link ClassReportStore} domain port. Each upsert
 * runs in its own short transaction — deliberately NOT wrapping the (slow) LLM
 * call — so a 10–20s Claude generation never holds a DB connection.
 */
@Component
@RequiredArgsConstructor
public class ClassReportStoreAdapter implements ClassReportStore {

    private final ClassReportJpaRepository jpa;

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredReport> find(String classId) {
        return jpa.findById(classId).map(this::toDomain);
    }

    @Override
    @Transactional
    public void markGenerating(String classId) {
        ClassReportJpaEntity e = load(classId);
        e.setStatus(ClassReportJpaEntity.STATUS_GENERATING);
        e.setError(null);
        e.setUpdatedAt(Instant.now());
        jpa.save(e);
    }

    @Override
    @Transactional
    public void markReady(String classId, String narrative, Instant generatedAt) {
        ClassReportJpaEntity e = load(classId);
        e.setStatus(ClassReportJpaEntity.STATUS_READY);
        e.setNarrative(narrative);
        e.setGeneratedAt(generatedAt);
        e.setError(null);
        e.setUpdatedAt(Instant.now());
        jpa.save(e);
    }

    @Override
    @Transactional
    public void markFailed(String classId, String error) {
        ClassReportJpaEntity e = load(classId);
        e.setStatus(ClassReportJpaEntity.STATUS_FAILED);
        e.setError(error);
        e.setUpdatedAt(Instant.now());
        jpa.save(e);
    }

    @Override
    @Transactional
    public void delete(String classId) {
        jpa.deleteById(classId);
    }

    private ClassReportJpaEntity load(String classId) {
        return jpa.findById(classId).orElseGet(() -> {
            ClassReportJpaEntity n = new ClassReportJpaEntity();
            n.setClassId(classId);
            return n;
        });
    }

    private StoredReport toDomain(ClassReportJpaEntity e) {
        return new StoredReport(e.getClassId(), e.getNarrative(), e.getStatus(),
                e.getError(), e.getGeneratedAt(), e.getUpdatedAt());
    }
}
