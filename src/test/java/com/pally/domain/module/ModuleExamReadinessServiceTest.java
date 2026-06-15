package com.pally.domain.module;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.infrastructure.persistence.module.LearningModuleJpaEntity;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaEntity;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaRepository;
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

    @Mock private LearningModuleJpaRepository moduleRepository;
    @Mock private ModuleProgressJpaRepository progressRepository;
    @Mock private AvatarRepository avatarRepository;

    private ModuleExamReadinessService service;

    @BeforeEach
    void setUp() {
        service = new ModuleExamReadinessService(
                moduleRepository, progressRepository, avatarRepository);
    }

    @Test
    void getExamPrep_aggregatesConceptMasteryWeakestFirst() {
        Avatar avatar = Avatar.create("user1", "Test", Subject.MATHS, CharacterType.ZAP);
        when(avatarRepository.findById(avatar.getId())).thenReturn(Optional.of(avatar));

        LearningModuleJpaEntity mod1 = buildModule("mod-1", "COMPLETE");
        mod1.setAvatarId(avatar.getId());
        mod1.setTitle("Fractions");
        LearningModuleJpaEntity mod2 = buildModule("mod-2", "COMPLETE");
        mod2.setAvatarId(avatar.getId());
        mod2.setTitle("Decimals");
        when(moduleRepository.findByAvatarId(avatar.getId())).thenReturn(List.of(mod1, mod2));

        ModuleProgressJpaEntity p1 = new ModuleProgressJpaEntity();
        p1.setStage("PROVE");
        p1.setTargetConcept("addition");
        p1.setScore(BigDecimal.valueOf(0.9));
        p1.setCompletedAt(Instant.now());

        ModuleProgressJpaEntity p2 = new ModuleProgressJpaEntity();
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
    void getExamPrep_avatarNotFound_throws() {
        when(avatarRepository.findById("bad-id")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getExamPrep("bad-id"))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void getClassExamReadiness_returnsSuggestionWhenBelow60() {
        LearningModuleJpaEntity mod1 = buildModule("mod-1", "COMPLETE");
        mod1.setMasteryPct(BigDecimal.valueOf(45.0));
        mod1.setClassId("class-1");
        LearningModuleJpaEntity mod2 = buildModule("mod-2", "COMPLETE");
        mod2.setMasteryPct(BigDecimal.valueOf(80.0));
        mod2.setClassId("class-1");
        when(moduleRepository.findByClassId("class-1")).thenReturn(List.of(mod1, mod2));

        Map<String, Object> result = service.getClassExamReadiness("class-1");

        assertThat((String) result.get("suggestion")).contains("revision");
        assertThat((int) result.get("completedModules")).isEqualTo(2);
    }

    private LearningModuleJpaEntity buildModule(String id, String stage) {
        LearningModuleJpaEntity m = new LearningModuleJpaEntity();
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
