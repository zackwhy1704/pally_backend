package com.pally.domain.marking;

import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Teacher visibility + removal for captured marking corrections (Part 4 — the
 * manual damper). Lets a teacher SEE what the marking assistant has learned from
 * their corrections and REMOVE a bad one so it never grounds future drafts.
 *
 * <p>Removal is the only damper this loop has that the student weakness loop gets
 * for free (a right answer clears a weakness). Removing an as-yet-PENDING
 * correction fully prevents it grounding any draft; removing an already-APPLIED
 * one excludes it from future feeds (the compile harness decays its residual
 * influence — see {@code MarkingCorpusService} — but does not instantly un-merge
 * it). The status field lets a teacher catch a bad correction before it applies.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarkingCorrectionService {

    private final MarkingCorrectionRepository repository;

    /** Active corrections for a class as teacher-facing DTOs (newest first). */
    public List<Map<String, Object>> listForClass(String classId) {
        return repository.findActiveByClassId(classId).stream()
                .map(MarkingCorrectionService::toDto)
                .toList();
    }

    /**
     * Soft-remove a correction so it's excluded from future recompiles + the view.
     * 404s if the correction doesn't exist or isn't in this class (tenant guard).
     */
    public void remove(String classId, String correctionId) {
        MarkingCorrection c = repository.findById(correctionId)
                .filter(x -> x.classId().equals(classId))
                .orElseThrow(() -> new BusinessException("Correction not found", 404));
        if (c.isRemoved()) return; // idempotent
        repository.markRemoved(correctionId, Instant.now());
        log.info("[Marking] teacher removed correction={} class={}", correctionId, classId);
    }

    private static Map<String, Object> toDto(MarkingCorrection c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("subject", c.subject());
        m.put("aiSuggestedGrade", c.aiSuggestedGrade());
        m.put("teacherGrade", c.teacherGrade());
        m.put("aiFeedback", c.aiFeedback());
        m.put("teacherFeedback", c.teacherFeedback());
        m.put("capturedAt", c.capturedAt() == null ? null : c.capturedAt().toString());
        // PENDING = not yet folded into the marking-wiki (removing it now fully
        // prevents it grounding any draft); APPLIED = already compiled in.
        m.put("status", c.compiledAt() == null ? "pending" : "applied");
        return m;
    }
}
