package com.pally.domain.module;

import com.pally.domain.module.dto.MasteryAuditResponse;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only audit over an already-computed mastery number: what evidence stands
 * behind it, and how much of that evidence is actually trustworthy.
 *
 * <p>Strictly a read-model. It computes NO mastery of its own and persists nothing —
 * {@code masteryPct} is reported exactly as {@link ModuleProgressionService} stored
 * it, and the breakdown is derived from {@code module_progress} rows on each call.
 * If this service ever starts producing a number the mastery math didn't, the
 * audit stops being an audit.
 */
@Service
@RequiredArgsConstructor
public class MasteryAuditService {

    private final LearningModuleRepository moduleRepository;
    private final ModuleProgressRepository progressRepository;
    private final ModuleAccessGuard moduleAccessGuard;
    private final GradingWeights gradingWeights;

    /**
     * @param userId the AUTHENTICATED caller — never a caller-supplied student id.
     *               This endpoint is self-scoped by construction; a teacher-facing
     *               view is a separate surface with its own guard, deliberately not
     *               built here.
     */
    @Transactional(readOnly = true)
    public MasteryAuditResponse audit(String moduleId, String userId) {
        LearningModule module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new BusinessException("Module not found", 404));
        moduleAccessGuard.assertModuleAccess(module, userId);

        List<ModuleProgress> rows = progressRepository.findByModuleIdAndUserId(moduleId, userId);

        Instant lastEvidenceAt = rows.stream()
                .map(ModuleProgress::getCompletedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        int contributingTotal = (int) rows.stream()
                .filter(ModuleProgressionService::contributesToMastery)
                .count();

        List<MasteryAuditResponse.TrustTier> breakdown = new ArrayList<>();
        for (GradingSignal signal : GradingSignal.values()) {
            breakdown.add(tierFor(signal.name(), rows,
                    p -> p.getSignalType() == signal,
                    gradingWeights.weightFor(signal)));
        }
        // Legacy pre-V111 rows carry a null signal_type and are weighted 1.0 by
        // GradingWeights.weightFor(null). Reported as their OWN tier so full-weight
        // never-verified evidence is never presented as DETERMINISTIC.
        breakdown.add(tierFor(MasteryAuditResponse.TIER_LEGACY_UNTYPED, rows,
                p -> p.getSignalType() == null,
                gradingWeights.weightFor(null)));

        return new MasteryAuditResponse(
                module.getId(),
                module.getTitle(),
                ModuleProgressionService.clampPct(module.getMasteryPct()),
                rows.size(),
                contributingTotal,
                lastEvidenceAt,
                List.copyOf(breakdown));
    }

    private MasteryAuditResponse.TrustTier tierFor(
            String tierName,
            List<ModuleProgress> rows,
            java.util.function.Predicate<ModuleProgress> inTier,
            double weight) {
        List<ModuleProgress> inThisTier = rows.stream().filter(inTier).toList();
        int contributing = (int) inThisTier.stream()
                .filter(ModuleProgressionService::contributesToMastery)
                .count();
        // Evidence MASS, not score: contributingCount x weight. Only contributing
        // rows count — a row excluded from the mastery math contributed nothing,
        // and claiming otherwise would inflate the very number being audited.
        return new MasteryAuditResponse.TrustTier(
                tierName, inThisTier.size(), contributing, weight, contributing * weight);
    }
}
