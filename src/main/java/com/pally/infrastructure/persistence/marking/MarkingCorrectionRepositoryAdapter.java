package com.pally.infrastructure.persistence.marking;

import com.pally.domain.marking.MarkingCorrection;
import com.pally.domain.marking.MarkingCorrectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Maps between {@link MarkingCorrection} and its JPA row. JPA never leaves here. */
@Component
@RequiredArgsConstructor
public class MarkingCorrectionRepositoryAdapter implements MarkingCorrectionRepository {

    private final MarkingCorrectionJpaRepository jpa;

    @Override
    @Transactional
    public MarkingCorrection save(MarkingCorrection c) {
        return toDomain(jpa.save(toEntity(c)));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<MarkingCorrection> findById(String id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MarkingCorrection> findActiveByClassId(String classId) {
        return jpa.findByClassIdAndRemovedAtIsNullOrderByCapturedAtDesc(classId)
                .stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MarkingCorrection> findUncompiledByClassId(String classId) {
        return jpa.findByClassIdAndCompiledAtIsNullAndRemovedAtIsNullOrderByCapturedAtAsc(classId)
                .stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public void markCompiled(List<String> ids, Instant compiledAt) {
        if (ids == null || ids.isEmpty()) return;
        for (String id : ids) {
            jpa.findById(id).ifPresent(e -> {
                e.setCompiledAt(compiledAt);
                jpa.save(e);
            });
        }
    }

    @Override
    @Transactional
    public void markRemoved(String id, Instant removedAt) {
        jpa.findById(id).ifPresent(e -> {
            e.setRemovedAt(removedAt);
            jpa.save(e);
        });
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private MarkingCorrectionJpaEntity toEntity(MarkingCorrection c) {
        MarkingCorrectionJpaEntity e = new MarkingCorrectionJpaEntity();
        e.setId(c.id());
        e.setSubmissionId(c.submissionId());
        e.setClassId(c.classId());
        e.setSubject(c.subject());
        e.setAiSuggestedGrade(c.aiSuggestedGrade());
        e.setAiFeedback(c.aiFeedback());
        e.setTeacherGrade(c.teacherGrade());
        e.setTeacherFeedback(c.teacherFeedback());
        e.setCapturedAt(c.capturedAt());
        e.setCompiledAt(c.compiledAt());
        e.setRemovedAt(c.removedAt());
        return e;
    }

    private MarkingCorrection toDomain(MarkingCorrectionJpaEntity e) {
        return new MarkingCorrection(
                e.getId(), e.getSubmissionId(), e.getClassId(), e.getSubject(),
                e.getAiSuggestedGrade(), e.getTeacherGrade(),
                e.getAiFeedback(), e.getTeacherFeedback(),
                e.getCapturedAt(), e.getCompiledAt(), e.getRemovedAt());
    }
}
