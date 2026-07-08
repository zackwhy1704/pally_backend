package com.pally.domain.module;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.shared.exception.AvatarNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ModuleExamReadinessService} (split out of the former
 * god ModuleService): per-concept exam-prep roll-up + class readiness.
 */
@ExtendWith(MockitoExtension.class)
class ModuleExamReadinessServiceTest {

    @Mock private LearningModuleRepository moduleRepository;
    @Mock private ModuleProgressRepository progressRepository;
    @Mock private AvatarRepository avatarRepository;

    private ModuleExamReadinessService service;

    @BeforeEach
    void setUp() {
        service = new ModuleExamReadinessService(
                moduleRepository, progressRepository, avatarRepository,
                new GradingWeights());
    }

    @Test
    void getExamPrep_aggregatesConceptMasteryWeakestFirst() {
        Avatar avatar = Avatar.create("user1", "Test", Subject.MATHS, CharacterType.ZAP);
        when(avatarRepository.findById(avatar.getId())).thenReturn(Optional.of(avatar));

        LearningModule mod1 = buildModule("mod-1", "COMPLETE");
        mod1.setAvatarId(avatar.getId());
        mod1.setTitle("Fractions");
        LearningModule mod2 = buildModule("mod-2", "COMPLETE");
        mod2.setAvatarId(avatar.getId());
        mod2.setTitle("Decimals");
        when(moduleRepository.findByAvatarId(avatar.getId())).thenReturn(List.of(mod1, mod2));

        ModuleProgress p1 = new ModuleProgress();
        p1.setStage("PROVE");
        p1.setTargetConcept("addition");
        p1.setScore(BigDecimal.valueOf(0.9));
        p1.setCompletedAt(Instant.now());

        ModuleProgress p2 = new ModuleProgress();
        p2.setStage("PROVE");
        p2.setTargetConcept("subtraction");
        p2.setScore(BigDecimal.valueOf(0.4));
        p2.setCompletedAt(Instant.now());

        when(progressRepository.findByModuleIdAndUserId("mod-1", avatar.getUserId()))
                .thenReturn(List.of(p1, p2));
        when(progressRepository.findByModuleIdAndUserId("mod-2", avatar.getUserId()))
                .thenReturn(List.of());

        Map<String, Object> result = service.getExamPrep(avatar.getId());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> concepts = (List<Map<String, Object>>) result.get("concepts");
        assertThat(concepts).hasSize(2);
        // Weakest first
        assertThat(concepts.get(0).get("concept")).isEqualTo("subtraction");
        assertThat((double) concepts.get(0).get("mastery")).isLessThan(50.0);
        assertThat(concepts.get(1).get("concept")).isEqualTo("addition");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getExamPrep_selfReportConcept_isTrustWeighted_notRaw100_consistentWithModuleMastery() {
        // The adjacent-surface inconsistency: a self-report YES (score 1.0) must NOT
        // show 100% concept mastery here while yielding 30% module mastery. It is
        // trust-weighted to 30% and labelled as SELF_REPORT.
        Avatar avatar = Avatar.create("user1", "Test", Subject.MATHS, CharacterType.ZAP);
        when(avatarRepository.findById(avatar.getId())).thenReturn(Optional.of(avatar));

        LearningModule mod = buildModule("mod-1", "COMPLETE");
        mod.setAvatarId(avatar.getId());
        when(moduleRepository.findByAvatarId(avatar.getId())).thenReturn(List.of(mod));

        ModuleProgress selfReport = new ModuleProgress();
        selfReport.setStage("PROVE");
        selfReport.setTargetConcept("photosynthesis");
        selfReport.setScore(BigDecimal.ONE);
        selfReport.setSignalType(GradingSignal.SELF_REPORT);
        when(progressRepository.findByModuleIdAndUserId("mod-1", avatar.getUserId()))
                .thenReturn(List.of(selfReport));

        Map<String, Object> result = service.getExamPrep(avatar.getId());
        List<Map<String, Object>> concepts =
                (List<Map<String, Object>>) result.get("concepts");

        assertThat((double) concepts.get(0).get("mastery")).isEqualTo(30.0);
        assertThat(concepts.get(0).get("signalType")).isEqualTo("SELF_REPORT");
    }

    @Test
    void getExamPrep_avatarNotFound_throws() {
        when(avatarRepository.findById("bad-id")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getExamPrep("bad-id"))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void getClassExamReadiness_returnsSuggestionWhenBelow60() {
        LearningModule mod1 = buildModule("mod-1", "COMPLETE");
        mod1.setMasteryPct(BigDecimal.valueOf(45.0));
        mod1.setClassId("class-1");
        LearningModule mod2 = buildModule("mod-2", "COMPLETE");
        mod2.setMasteryPct(BigDecimal.valueOf(80.0));
        mod2.setClassId("class-1");
        when(moduleRepository.findByClassId("class-1")).thenReturn(List.of(mod1, mod2));

        Map<String, Object> result = service.getClassExamReadiness("class-1");

        assertThat((String) result.get("suggestion")).contains("revision");
        assertThat((int) result.get("completedModules")).isEqualTo(2);
    }

    private LearningModule buildModule(String id, String stage) {
        LearningModule m = new LearningModule();
        m.setId(id);
        m.setAvatarId("avatar-1");
        m.setWikiPageSlug("slug");
        m.setTitle("Title");
        m.setStage(stage);
        m.setTier("FREE");
        m.setMasteryPct(BigDecimal.ZERO);
        m.setCreatedAt(Instant.now());
        return m;
    }
}
