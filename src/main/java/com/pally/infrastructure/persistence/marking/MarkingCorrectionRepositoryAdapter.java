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
    public List<MarkingCorrection> findByClassId(String classId) {
        return jpa.findByClassIdOrderByCapturedAtDesc(classId).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MarkingCorrection> findUncompiledByClassId(String classId) {
        return jpa.findByClassIdAndCompiledAtIsNullOrderByCapturedAtAsc(classId)
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
        return e;
    }

    private MarkingCorrection toDomain(MarkingCorrectionJpaEntity e) {
        return new MarkingCorrection(
                e.getId(), e.getSubmissionId(), e.getClassId(), e.getSubject(),
                e.getAiSuggestedGrade(), e.getTeacherGrade(),
                e.getAiFeedback(), e.getTeacherFeedback(),
                e.getCapturedAt(), e.getCompiledAt());
    }
}
