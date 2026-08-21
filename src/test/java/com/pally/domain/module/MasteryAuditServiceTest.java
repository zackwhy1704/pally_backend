package com.pally.domain.module;

import com.pally.domain.module.dto.MasteryAuditResponse;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The product claim under test: two students can show the SAME headline mastery while
 * one's number rests on server-verified evidence and the other's rests on
 * self-assessment — and this endpoint must make that difference visible. If these
 * tests pass with the breakdown logic neutered, the breakdown is cosmetic.
 */
@ExtendWith(MockitoExtension.class)
class MasteryAuditServiceTest {

    private static final String MODULE_ID = "mod-1";
    private static final String AVATAR_ID = "av-1";

    @Mock private LearningModuleRepository moduleRepository;
    @Mock private ModuleProgressRepository progressRepository;
    @Mock private com.pally.domain.avatar.AvatarRepository avatarRepository;
    @Mock private com.pally.domain.centre.CentreAccessService centreAccessService;

    private MasteryAuditService service;

    @BeforeEach
    void setUp() {
        service = new MasteryAuditService(
                moduleRepository, progressRepository,
                new ModuleAccessGuard(avatarRepository, centreAccessService),
                new GradingWeights());
        lenient().when(avatarRepository.existsByIdAndUserId(anyString(), anyString()))
                .thenReturn(true);
    }

    private LearningModule module(String masteryPct) {
        LearningModule m = new LearningModule();
        m.setId(MODULE_ID);
        m.setAvatarId(AVATAR_ID);
        m.setTitle("Fractions");
        m.setMasteryPct(new BigDecimal(masteryPct));
        return m;
    }

    private ModuleProgress row(String stage, GradingSignal signal, String score) {
        ModuleProgress p = new ModuleProgress();
        p.setModuleId(MODULE_ID);
        p.setStage(stage);
        p.setSignalType(signal);
        p.setScore(score == null ? null : new BigDecimal(score));
        p.setCompletedAt(Instant.parse("2026-08-20T10:00:00Z"));
        return p;
    }

    private MasteryAuditResponse.TrustTier tier(MasteryAuditResponse r, String name) {
        return r.trustBreakdown().stream()
                .filter(t -> t.tier().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tier missing from breakdown: " + name));
    }

    private MasteryAuditResponse auditWith(String masteryPct, List<ModuleProgress> rows) {
        when(moduleRepository.findById(MODULE_ID)).thenReturn(Optional.of(module(masteryPct)));
        when(progressRepository.findByModuleIdAndUserId(MODULE_ID, "user-1")).thenReturn(rows);
        return service.audit(MODULE_ID, "user-1");
    }

    // ── THE PRODUCT CLAIM ────────────────────────────────────────────────────

    @Test
    void sameHeadlineMastery_deterministicHeavyVsSelfReportHeavy_reportDifferentBreakdowns() {
        // Student A: 4 server-graded TEST answers. Genuinely verified.
        MasteryAuditResponse verified = auditWith("80.00", List.of(
                row("TEST", GradingSignal.DETERMINISTIC, "0.8"),
                row("TEST", GradingSignal.DETERMINISTIC, "0.8"),
                row("TEST", GradingSignal.DETERMINISTIC, "0.8"),
                row("TEST", GradingSignal.DETERMINISTIC, "0.8")));

        // Student B: same 80.00 headline, but every row is the student's own say-so.
        MasteryAuditResponse selfAssessed = auditWith("80.00", List.of(
                row("PROVE", GradingSignal.SELF_REPORT, "0.8"),
                row("PROVE", GradingSignal.SELF_REPORT, "0.8"),
                row("PROVE", GradingSignal.SELF_REPORT, "0.8"),
                row("PROVE", GradingSignal.SELF_REPORT, "0.8")));

        // Identical headline — indistinguishable without the breakdown.
        assertThat(verified.masteryPct()).isEqualByComparingTo(selfAssessed.masteryPct());
        assertThat(verified.evidenceCount()).isEqualTo(selfAssessed.evidenceCount());

        // The breakdown is what separates them.
        assertThat(tier(verified, "DETERMINISTIC").contributingCount()).isEqualTo(4);
        assertThat(tier(verified, "SELF_REPORT").contributingCount()).isZero();
        assertThat(tier(selfAssessed, "DETERMINISTIC").contributingCount()).isZero();
        assertThat(tier(selfAssessed, "SELF_REPORT").contributingCount()).isEqualTo(4);

        // And the weighted evidence mass differs by the real trust ratio: 4.0 vs 1.2.
        assertThat(tier(verified, "DETERMINISTIC").weightedContribution())
                .isEqualTo(4.0, within(1e-9));
        assertThat(tier(selfAssessed, "SELF_REPORT").weightedContribution())
                .isEqualTo(1.2, within(1e-9));
        assertThat(tier(verified, "DETERMINISTIC").weightedContribution())
                .as("verified evidence must outweigh the same volume of self-report")
                .isGreaterThan(tier(selfAssessed, "SELF_REPORT").weightedContribution());
    }

    @Test
    void trustWeightIsNeverCollapsedIntoTheScore() {
        // The getExamPrep defect this endpoint exists to avoid: reporting
        // weight x score, which makes a genuinely-mastered-but-self-reported topic
        // indistinguishable from a genuinely-weak one. masteryPct must come back
        // untouched, with the weight reported alongside it instead.
        MasteryAuditResponse r = auditWith("80.00", List.of(
                row("PROVE", GradingSignal.SELF_REPORT, "0.8")));

        assertThat(r.masteryPct()).isEqualByComparingTo(new BigDecimal("80.00"));
        assertThat(tier(r, "SELF_REPORT").weight()).isEqualTo(0.30, within(1e-9));
    }

    // ── EVIDENCE COUNT HONESTY ───────────────────────────────────────────────

    @Test
    void evidenceCount_countsAllRows_butContributingCountExcludesNonGradedOnes() {
        MasteryAuditResponse r = auditWith("50.00", List.of(
                row("LEARN", null, "1.0"),                          // completion marker
                row("TEST", GradingSignal.UNGRADED, null),          // no trustworthy signal
                row("TEST", GradingSignal.DETERMINISTIC, "0.5")));  // the only real evidence

        assertThat(r.evidenceCount()).isEqualTo(3);
        assertThat(r.masteryContributingCount())
                .as("LEARN + UNGRADED never fed masteryPct — reporting 3 would overstate it")
                .isEqualTo(1);
    }

    @Test
    void ungradedRows_areVisibleButContributeZeroMass() {
        MasteryAuditResponse r = auditWith("0.00", List.of(
                row("TEST", GradingSignal.UNGRADED, null),
                row("TEST", GradingSignal.UNGRADED, null)));

        assertThat(tier(r, "UNGRADED").count()).isEqualTo(2);
        assertThat(tier(r, "UNGRADED").contributingCount()).isZero();
        assertThat(tier(r, "UNGRADED").weightedContribution()).isEqualTo(0.0, within(1e-9));
    }

    // ── LEGACY UNTYPED ROWS ──────────────────────────────────────────────────

    @Test
    void legacyUntypedRows_reportAsTheirOwnTier_notAsDeterministic() {
        // weightFor(null) == 1.0, so these carry FULL weight despite never having
        // been server-verified. Folding them into DETERMINISTIC would claim
        // verification that never happened.
        MasteryAuditResponse r = auditWith("70.00", List.of(
                row("PROVE", null, "0.7")));

        assertThat(tier(r, MasteryAuditResponse.TIER_LEGACY_UNTYPED).contributingCount())
                .isEqualTo(1);
        assertThat(tier(r, MasteryAuditResponse.TIER_LEGACY_UNTYPED).weight())
                .isEqualTo(1.0, within(1e-9));
        assertThat(tier(r, "DETERMINISTIC").count())
                .as("a legacy untyped row must never be presented as server-verified")
                .isZero();
    }

    @Test
    void legacyUntypedTestRows_areExcludedFromMastery_theClosedSpoofHole() {
        // Legacy null-signal TEST rows are the OLD client-authoritative scores.
        // They must appear as evidence but never count toward mastery.
        MasteryAuditResponse r = auditWith("0.00", List.of(
                row("TEST", null, "1.0")));

        assertThat(tier(r, MasteryAuditResponse.TIER_LEGACY_UNTYPED).count()).isEqualTo(1);
        assertThat(tier(r, MasteryAuditResponse.TIER_LEGACY_UNTYPED).contributingCount())
                .as("resurrecting legacy TEST scores would reopen the spoof hole")
                .isZero();
        assertThat(r.masteryContributingCount()).isZero();
    }

    // ── SHAPE + ACCESS ───────────────────────────────────────────────────────

    @Test
    void everyTierIsAlwaysPresent_evenAtZero() {
        MasteryAuditResponse r = auditWith("0.00", List.of());

        assertThat(r.trustBreakdown()).hasSize(4);
        assertThat(r.trustBreakdown()).extracting(MasteryAuditResponse.TrustTier::tier)
                .containsExactlyInAnyOrder("DETERMINISTIC", "SELF_REPORT", "UNGRADED",
                        MasteryAuditResponse.TIER_LEGACY_UNTYPED);
        assertThat(r.evidenceCount()).isZero();
        assertThat(r.lastEvidenceAt()).isNull();
    }

    @Test
    void masteryPct_isClampedToTheZeroToHundredContract() {
        MasteryAuditResponse r = auditWith("2600.00", List.of());

        assertThat(r.masteryPct())
                .as("the documented >100% legacy bug class must never render")
                .isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    void lastEvidenceAt_isTheMostRecentCompletedAt() {
        ModuleProgress older = row("TEST", GradingSignal.DETERMINISTIC, "0.5");
        older.setCompletedAt(Instant.parse("2026-08-01T10:00:00Z"));
        ModuleProgress newer = row("TEST", GradingSignal.DETERMINISTIC, "0.5");
        newer.setCompletedAt(Instant.parse("2026-08-19T10:00:00Z"));

        MasteryAuditResponse r = auditWith("50.00", List.of(older, newer));

        assertThat(r.lastEvidenceAt()).isEqualTo(Instant.parse("2026-08-19T10:00:00Z"));
    }

    @Test
    void nonOwnerNonMember_gets404_notSomeoneElsesAudit() {
        when(avatarRepository.existsByIdAndUserId(anyString(), anyString())).thenReturn(false);
        when(centreAccessService.isActiveClassMember(anyString(), any())).thenReturn(false);
        when(moduleRepository.findById(MODULE_ID)).thenReturn(Optional.of(module("80.00")));

        assertThatThrownBy(() -> service.audit(MODULE_ID, "attacker"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Module not found");
    }

    @Test
    void missingModule_gets404() {
        when(moduleRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.audit("nope", "user-1"))
                .isInstanceOf(BusinessException.class);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
