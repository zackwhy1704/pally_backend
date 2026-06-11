package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.infrastructure.persistence.module.LearningModuleJpaEntity;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.infrastructure.persistence.module.ModuleContentItemJpaEntity;
import com.pally.infrastructure.persistence.module.ModuleContentItemJpaRepository;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaEntity;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModuleServiceTest {

    @Mock private LearningModuleJpaRepository moduleRepository;
    @Mock private ModuleContentItemJpaRepository itemRepository;
    @Mock private ModuleProgressJpaRepository progressRepository;
    @Mock private ModuleContentGenerator contentGenerator;
    @Mock private ModuleProveEvaluator proveEvaluator;
    @Mock private AvatarRepository avatarRepository;
    @Mock private WikiRepository wikiRepository;

    private ModuleService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ModuleService(
                moduleRepository, itemRepository, progressRepository,
                contentGenerator, proveEvaluator, avatarRepository,
                wikiRepository, objectMapper);
    }

    // ── generateModules ─────────────────────────────────────────────────

    @Test
    void generateModules_avatarNotFound_throws() {
        when(avatarRepository.findById("bad-id")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.generateModules("bad-id"))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void generateModules_skipsExistingSlugs() {
        Avatar avatar = Avatar.create("user1", "Test", Subject.MATHS, CharacterType.ZAP);
        when(avatarRepository.findById(avatar.getId())).thenReturn(Optional.of(avatar));

        WikiPage page = WikiPage.create(avatar.getId(), "fractions", "Fractions", "Content");
        when(wikiRepository.findByAvatarId(avatar.getId())).thenReturn(List.of(page));

        // Module already exists for this slug
        when(moduleRepository.findByAvatarIdAndWikiPageSlug(avatar.getId(), "fractions"))
                .thenReturn(Optional.of(new LearningModuleJpaEntity()));

        List<LearningModuleJpaEntity> result = service.generateModules(avatar.getId());
        assertThat(result).isEmpty();
        verify(contentGenerator, never()).generate(any(), any());
    }

    @Test
    void generateModules_createsModuleForNewPage() {
        Avatar avatar = Avatar.create("user1", "Test", Subject.MATHS, CharacterType.ZAP);
        when(avatarRepository.findById(avatar.getId())).thenReturn(Optional.of(avatar));

        WikiPage page = WikiPage.create(avatar.getId(), "fractions", "Fractions", "Content");
        when(wikiRepository.findByAvatarId(avatar.getId())).thenReturn(List.of(page));
        when(moduleRepository.findByAvatarIdAndWikiPageSlug(avatar.getId(), "fractions"))
                .thenReturn(Optional.empty());

        LearningModuleJpaEntity module = new LearningModuleJpaEntity();
        module.setId("mod-1");
        when(contentGenerator.generate(avatar, page)).thenReturn(module);

        List<LearningModuleJpaEntity> result = service.generateModules(avatar.getId());
        assertThat(result).hasSize(1);
        verify(contentGenerator).generate(avatar, page);
    }

    // ── startModule ─────────────────────────────────────────────────────

    @Test
    void startModule_completedModule_entersRevisionMode() {
        LearningModuleJpaEntity module = buildModule("mod-1", "COMPLETE");
        module.setAvatarId("avatar-1");
        module.setWikiPageSlug("test-slug");
        module.setTier("FREE");
        module.setCompletedAt(Instant.now());
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));
        when(moduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Revision generates new PROVE items — generateProveItemsAdaptively needs these
        when(progressRepository.findByModuleIdAndUserId("mod-1", "user-1"))
                .thenReturn(List.of());
        WikiPage page = WikiPage.create("avatar-1", "test-slug", "Test", "Content");
        when(wikiRepository.findByAvatarIdAndSlug("avatar-1", "test-slug"))
                .thenReturn(Optional.of(page));
        when(contentGenerator.generateProveQuestions(eq(module), eq(page), anyList(), eq("FREE")))
                .thenReturn(List.of());
        when(itemRepository.findByModuleIdAndStageOrderBySortOrder("mod-1", "PROVE"))
                .thenReturn(List.of());

        Map<String, Object> result = service.startModule("mod-1", "user-1");

        assertThat(result.get("stage")).isEqualTo("PROVE");
        assertThat(result.get("revision")).isEqualTo(true);
        // Module stage should be set back to PROVE
        verify(moduleRepository).save(argThat(m -> "PROVE".equals(m.getStage())));
    }

    @Test
    void startModule_proveStage_generatesAdaptiveQuestionsWhenNoneExist() {
        LearningModuleJpaEntity module = buildModule("mod-1", "PROVE");
        module.setAvatarId("avatar-1");
        module.setWikiPageSlug("test-slug");
        module.setTier("FREE");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));
        when(itemRepository.countByModuleIdAndStage("mod-1", "PROVE")).thenReturn(0);
        when(itemRepository.findByModuleIdAndStageOrderBySortOrder("mod-1", "PROVE"))
                .thenReturn(List.of());
        when(progressRepository.findByModuleIdAndUserId("mod-1", "user-1"))
                .thenReturn(List.of());

        WikiPage page = WikiPage.create("avatar-1", "test-slug", "Test", "Content");
        when(wikiRepository.findByAvatarIdAndSlug("avatar-1", "test-slug"))
                .thenReturn(Optional.of(page));
        when(contentGenerator.generateProveQuestions(eq(module), eq(page), anyList(), eq("FREE")))
                .thenReturn(List.of());

        service.startModule("mod-1", "user-1");

        verify(contentGenerator).generateProveQuestions(eq(module), eq(page), anyList(), eq("FREE"));
    }

    // ── submitAnswers ───────────────────────────────────────────────────

    @Test
    void submitAnswers_wrongStage_throws400() {
        LearningModuleJpaEntity module = buildModule("mod-1", "LEARN");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItemJpaEntity item = new ModuleContentItemJpaEntity();
        item.setId("item-1");
        item.setStage("TEST"); // wrong stage!
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("itemId", "item-1", "response", "{}"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current stage is LEARN");
    }

    @Test
    void submitAnswers_completedModule_throws400() {
        LearningModuleJpaEntity module = buildModule("mod-1", "COMPLETE");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        assertThatThrownBy(() -> service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("itemId", "item-1", "response", "{}"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already completed");
    }

    @Test
    void submitAnswers_allLearnItemsCompleted_advancesToTest() {
        LearningModuleJpaEntity module = buildModule("mod-1", "LEARN");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItemJpaEntity item = new ModuleContentItemJpaEntity();
        item.setId("item-1");
        item.setStage("LEARN");
        item.setType("MICRO_CARD");
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));

        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "item-1"))
                .thenReturn(Optional.empty());
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // All items completed
        when(itemRepository.countByModuleIdAndStage("mod-1", "LEARN")).thenReturn(1);
        when(progressRepository.countByModuleIdAndUserIdAndStage("mod-1", "user-1", "LEARN"))
                .thenReturn(1);

        Map<String, Object> result = service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("itemId", "item-1", "response", "{\"viewed\":true}")));

        assertThat(result.get("stageComplete")).isEqualTo(true);
        assertThat(result.get("nextStage")).isEqualTo("TEST");
        verify(moduleRepository).save(any());
    }

    @Test
    void submitAnswers_notAllItemsCompleted_doesNotAdvance() {
        LearningModuleJpaEntity module = buildModule("mod-1", "LEARN");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItemJpaEntity item = new ModuleContentItemJpaEntity();
        item.setId("item-1");
        item.setStage("LEARN");
        item.setType("MICRO_CARD");
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));

        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "item-1"))
                .thenReturn(Optional.empty());
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 2 total items, only 1 completed
        when(itemRepository.countByModuleIdAndStage("mod-1", "LEARN")).thenReturn(2);
        when(progressRepository.countByModuleIdAndUserIdAndStage("mod-1", "user-1", "LEARN"))
                .thenReturn(1);

        Map<String, Object> result = service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("itemId", "item-1", "response", "{\"viewed\":true}")));

        assertThat(result.get("stageComplete")).isEqualTo(false);
        assertThat(result.get("nextStage")).isNull();
    }

    @Test
    void submitAnswers_proveStage_evaluatesViaProveEvaluator() {
        LearningModuleJpaEntity module = buildModule("mod-1", "PROVE");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItemJpaEntity item = new ModuleContentItemJpaEntity();
        item.setId("item-1");
        item.setStage("PROVE");
        item.setType("PROVE_QUESTION");
        item.setContentJson("{\"question\":\"Q\",\"targetConcept\":\"C\"}");
        item.setAnswerJson("{\"expectedKeyPoints\":[\"kp1\"],\"targetConcept\":\"C\"}");
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));

        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "item-1"))
                .thenReturn(Optional.empty());
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(proveEvaluator.evaluateAnswer(eq(item), eq("My answer")))
                .thenReturn(new ModuleProveEvaluator.ProveResult(
                        true, List.of("kp1"), List.of(), "Great!", 0.85));

        when(itemRepository.countByModuleIdAndStage("mod-1", "PROVE")).thenReturn(2);
        when(progressRepository.countByModuleIdAndUserIdAndStage("mod-1", "user-1", "PROVE"))
                .thenReturn(1);

        Map<String, Object> result = service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("itemId", "item-1", "response", "My answer")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("score")).isEqualTo(0.85);
        assertThat(results.get(0).get("conceptCovered")).isEqualTo(true);
    }

    @Test
    void submitAnswers_missingItemId_throws400() {
        LearningModuleJpaEntity module = buildModule("mod-1", "LEARN");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        assertThatThrownBy(() -> service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("response", "test"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("itemId is required");
    }

    // ── getExamPrep ──────────────────────────────────────────────────────

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

        // PROVE progress for mod1
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

    // ── getClassExamReadiness ──────────────────────────────────────────

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

    // ── Stage enum ──────────────────────────────────────────────────────

    @Test
    void moduleStage_nextReturnsCorrectSequence() {
        assertThat(ModuleStage.LEARN.next()).isEqualTo(ModuleStage.TEST);
        assertThat(ModuleStage.TEST.next()).isEqualTo(ModuleStage.PROVE);
        assertThat(ModuleStage.PROVE.next()).isEqualTo(ModuleStage.COMPLETE);
        assertThat(ModuleStage.COMPLETE.next()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────

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
