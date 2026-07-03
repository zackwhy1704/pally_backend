package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
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

/**
 * Unit tests for {@link ModuleProgressionService} (split out of the former god
 * ModuleService): the LEARN → TEST → PROVE → COMPLETE lifecycle, stage advance,
 * PROVE evaluation + certainty nudge, and revision mode.
 */
@ExtendWith(MockitoExtension.class)
class ModuleProgressionServiceTest {

    @Mock private LearningModuleRepository moduleRepository;
    @Mock private ModuleContentItemRepository itemRepository;
    @Mock private ModuleProgressRepository progressRepository;
    @Mock private ModuleContentGenerator contentGenerator;
    @Mock private ModuleProveEvaluator proveEvaluator;
    @Mock private WikiRepository wikiRepository;
    @Mock private com.pally.domain.notification.MilestoneNotifier milestoneNotifier;
    @Mock private com.pally.domain.progress.ActivityLogService activityLogService;
    @Mock private com.pally.domain.progress.XpService xpService;
    @Mock private com.pally.domain.avatar.AvatarRepository avatarRepository;
    @Mock private com.pally.domain.centre.CentreAccessService centreAccessService;
    @Mock private com.pally.domain.weakness.WeaknessProfileService weaknessProfileService;

    private ModuleProgressionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ModuleProgressionService(
                moduleRepository, itemRepository, progressRepository,
                contentGenerator, proveEvaluator, wikiRepository, objectMapper,
                milestoneNotifier, activityLogService, xpService,
                avatarRepository, centreAccessService, weaknessProfileService);
        // Default: caller owns the module's avatar, so the access guard passes.
        // Individual tests override this to exercise the IDOR rejection.
        lenient().when(avatarRepository.existsByIdAndUserId(anyString(), anyString()))
                .thenReturn(true);
    }

    // ── access guard (IDOR) ─────────────────────────────────────────────

    @Test
    void getModuleDetail_callerNeitherOwnerNorEnrolled_throws404() {
        LearningModule module = buildModule("mod-x", "LEARN");
        module.setAvatarId("avatar-x");
        module.setClassId("class-x");
        when(moduleRepository.findById("mod-x")).thenReturn(Optional.of(module));
        // Not the avatar's owner AND not an active member of its class.
        when(avatarRepository.existsByIdAndUserId("avatar-x", "attacker")).thenReturn(false);
        when(centreAccessService.isActiveClassMember("attacker", "class-x")).thenReturn(false);

        assertThatThrownBy(() -> service.getModuleDetail("mod-x", "attacker"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
    }

    @Test
    void startModule_centreStudentEnrolledInClass_isAllowed() {
        LearningModule module = buildModule("mod-c", "LEARN");
        module.setAvatarId("corpus-avatar");   // owned by the centre, not the student
        module.setClassId("class-c");
        when(moduleRepository.findById("mod-c")).thenReturn(Optional.of(module));
        when(avatarRepository.existsByIdAndUserId("corpus-avatar", "student")).thenReturn(false);
        when(centreAccessService.isActiveClassMember("student", "class-c")).thenReturn(true);
        when(itemRepository.findByModuleIdAndStageOrderBySortOrder("mod-c", "LEARN"))
                .thenReturn(List.of());

        Map<String, Object> result = service.startModule("mod-c", "student");

        assertThat(result.get("stage")).isEqualTo("LEARN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void startModule_returnedItemsCarryStageForMobileParser() {
        LearningModule module = buildModule("mod-s", "LEARN");
        when(moduleRepository.findById("mod-s")).thenReturn(Optional.of(module));

        ModuleContentItem item = new ModuleContentItem();
        item.setId("i-learn");
        item.setStage("LEARN");
        item.setType("MICRO_CARD");
        item.setContentJson("{\"title\":\"T\",\"body\":\"B\"}");
        item.setAnswerJson("{\"k\":\"v\"}");
        item.setSortOrder(0);
        when(itemRepository.findByModuleIdAndStageOrderBySortOrder("mod-s", "LEARN"))
                .thenReturn(List.of(item));

        Map<String, Object> result = service.startModule("mod-s", "user-1");

        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        Map<String, Object> m = items.get(0);
        // Regression: the mobile ModuleContentItem parser requires `stage`; the
        // start path historically omitted it, which emptied the lesson on the
        // client and surfaced "Something went wrong loading this lesson".
        assertThat(m).containsKeys("id", "stage", "type", "contentJson", "sortOrder");
        assertThat(m.get("stage")).isEqualTo("LEARN");
        assertThat(m.get("answerJson")).isNotNull(); // LEARN items expose answerJson
    }

    // ── getModuleDetail: teacher-reviewed badge (C3) ─────────────────────

    @Test
    void getModuleDetail_centreModuleAllApproved_isTeacherReviewed() {
        LearningModule module = buildModule("mod-r", "LEARN");
        module.setClassId("class-c");
        when(moduleRepository.findById("mod-r")).thenReturn(Optional.of(module));
        when(avatarRepository.existsByIdAndUserId("avatar-1", "u1")).thenReturn(true);
        ModuleContentItem item = new ModuleContentItem();
        item.setId("i1");
        item.setStatus("APPROVED");
        when(itemRepository.findByModuleIdOrderBySortOrder("mod-r")).thenReturn(List.of(item));
        when(progressRepository.findByModuleIdAndUserId("mod-r", "u1")).thenReturn(List.of());

        assertThat(service.getModuleDetail("mod-r", "u1").get("teacherReviewed")).isEqualTo(true);
    }

    @Test
    void getModuleDetail_personalModule_isNotTeacherReviewed() {
        LearningModule module = buildModule("mod-p", "LEARN"); // classId null
        when(moduleRepository.findById("mod-p")).thenReturn(Optional.of(module));
        when(avatarRepository.existsByIdAndUserId("avatar-1", "u1")).thenReturn(true);
        ModuleContentItem item = new ModuleContentItem();
        item.setId("i1");
        item.setStatus("LIVE");
        when(itemRepository.findByModuleIdOrderBySortOrder("mod-p")).thenReturn(List.of(item));
        when(progressRepository.findByModuleIdAndUserId("mod-p", "u1")).thenReturn(List.of());

        assertThat(service.getModuleDetail("mod-p", "u1").get("teacherReviewed")).isEqualTo(false);
    }

    // ── startModule ─────────────────────────────────────────────────────

    @Test
    void startModule_completedModule_entersRevisionMode() {
        LearningModule module = buildModule("mod-1", "COMPLETE");
        module.setAvatarId("avatar-1");
        module.setWikiPageSlug("test-slug");
        module.setTier("FREE");
        module.setCompletedAt(Instant.now());
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));
        when(moduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

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
        verify(moduleRepository).save(argThat(m -> "PROVE".equals(m.getStage())));
    }

    @Test
    void startModule_proveStage_generatesAdaptiveQuestionsWhenNoneExist() {
        LearningModule module = buildModule("mod-1", "PROVE");
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
        LearningModule module = buildModule("mod-1", "LEARN");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");
        item.setStage("TEST");
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("itemId", "item-1", "response", "{}"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("current stage is LEARN");
    }

    @Test
    void submitAnswers_completedModule_throws400() {
        LearningModule module = buildModule("mod-1", "COMPLETE");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        assertThatThrownBy(() -> service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("itemId", "item-1", "response", "{}"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already completed");
    }

    @Test
    void submitAnswers_allLearnItemsCompleted_advancesToTest() {
        LearningModule module = buildModule("mod-1", "LEARN");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");
        item.setStage("LEARN");
        item.setType("MICRO_CARD");
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));

        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "item-1"))
                .thenReturn(Optional.empty());
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

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
    void submitAnswers_stageComplete_logsRealDuration() {
        LearningModule module = buildModule("mod-1", "LEARN");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");
        item.setStage("LEARN");
        item.setType("MICRO_CARD");
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));

        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "item-1"))
                .thenReturn(Optional.empty());
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.countByModuleIdAndStage("mod-1", "LEARN")).thenReturn(1);
        when(progressRepository.countByModuleIdAndUserIdAndStage("mod-1", "user-1", "LEARN"))
                .thenReturn(1);

        service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("itemId", "item-1", "response", "{\"viewed\":true}")),
                150);

        verify(activityLogService).log(eq("user-1"), eq("avatar-1"),
                eq(com.pally.domain.progress.ActivityLogService.TYPE_QUIZ),
                eq(150), anyInt());
    }

    @Test
    void submitAnswers_legacyOverload_logsZeroDuration() {
        LearningModule module = buildModule("mod-1", "LEARN");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");
        item.setStage("LEARN");
        item.setType("MICRO_CARD");
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));

        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "item-1"))
                .thenReturn(Optional.empty());
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.countByModuleIdAndStage("mod-1", "LEARN")).thenReturn(1);
        when(progressRepository.countByModuleIdAndUserIdAndStage("mod-1", "user-1", "LEARN"))
                .thenReturn(1);

        service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("itemId", "item-1", "response", "{\"viewed\":true}")));

        verify(activityLogService).log(eq("user-1"), eq("avatar-1"),
                eq(com.pally.domain.progress.ActivityLogService.TYPE_QUIZ),
                eq(0), anyInt());
    }

    @Test
    void submitAnswers_notAllItemsCompleted_doesNotAdvance() {
        LearningModule module = buildModule("mod-1", "LEARN");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");
        item.setStage("LEARN");
        item.setType("MICRO_CARD");
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));

        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "item-1"))
                .thenReturn(Optional.empty());
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

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
        LearningModule module = buildModule("mod-1", "PROVE");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItem item = new ModuleContentItem();
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
        LearningModule module = buildModule("mod-1", "LEARN");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        assertThatThrownBy(() -> service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("response", "test"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("itemId is required");
    }

    // ── PROVE results nudge wiki certaintyScore ───────────────────────────

    @Test
    void adjustCertaintyForProve_strongScore_raisesCertaintyBy005() {
        LearningModule module = buildModule("mod-1", "PROVE");
        module.setAvatarId("avatar-1");
        module.setWikiPageSlug("photosynthesis");

        double delta = service.adjustCertaintyForProve(module, 0.9);

        assertThat(delta).isEqualTo(0.05);
        verify(wikiRepository).adjustCertainty("avatar-1", List.of("photosynthesis"), 0.05);
    }

    @Test
    void adjustCertaintyForProve_weakScore_lowersCertaintyBy008() {
        LearningModule module = buildModule("mod-1", "PROVE");
        module.setAvatarId("avatar-1");
        module.setWikiPageSlug("photosynthesis");

        double delta = service.adjustCertaintyForProve(module, 0.2);

        assertThat(delta).isEqualTo(-0.08);
        verify(wikiRepository).adjustCertainty("avatar-1", List.of("photosynthesis"), -0.08);
    }

    @Test
    void adjustCertaintyForProve_middlingScore_leavesCertaintyUntouched() {
        LearningModule module = buildModule("mod-1", "PROVE");
        module.setAvatarId("avatar-1");
        module.setWikiPageSlug("photosynthesis");

        double delta = service.adjustCertaintyForProve(module, 0.5);

        assertThat(delta).isEqualTo(0.0);
        verify(wikiRepository, never()).adjustCertainty(anyString(), anyList(), anyDouble());
    }

    @Test
    void submitAnswers_proveStage_nudgesWikiCertainty() {
        LearningModule module = buildModule("mod-1", "PROVE");
        module.setAvatarId("avatar-1");
        module.setWikiPageSlug("photosynthesis");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");
        item.setStage("PROVE");
        item.setType("PROVE_QUESTION");
        item.setContentJson("{\"question\":\"Q\",\"targetConcept\":\"C\"}");
        item.setAnswerJson("{\"expectedKeyPoints\":[\"kp1\"],\"targetConcept\":\"C\"}");
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));

        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "item-1"))
                .thenReturn(Optional.empty());
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(proveEvaluator.evaluateAnswer(eq(item), eq("My strong answer")))
                .thenReturn(new ModuleProveEvaluator.ProveResult(
                        true, List.of("kp1"), List.of(), "Great!", 0.9));
        when(itemRepository.countByModuleIdAndStage("mod-1", "PROVE")).thenReturn(2);
        when(progressRepository.countByModuleIdAndUserIdAndStage("mod-1", "user-1", "PROVE"))
                .thenReturn(1);

        service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("itemId", "item-1", "response", "My strong answer")));

        verify(wikiRepository).adjustCertainty("avatar-1", List.of("photosynthesis"), 0.05);
    }

    // ── Stage enum ──────────────────────────────────────────────────────

    @Test
    void moduleStage_nextReturnsCorrectSequence() {
        assertThat(ModuleStage.LEARN.next()).isEqualTo(ModuleStage.TEST);
        assertThat(ModuleStage.TEST.next()).isEqualTo(ModuleStage.PROVE);
        assertThat(ModuleStage.PROVE.next()).isEqualTo(ModuleStage.COMPLETE);
        assertThat(ModuleStage.COMPLETE.next()).isNull();
    }

    @Test
    void getModulePreview_moduleInDifferentClass_throws404_andNeverReadsItems() {
        LearningModule module = buildModule("mod-p", "LEARN");
        module.setClassId("class-A");
        when(moduleRepository.findById("mod-p")).thenReturn(java.util.Optional.of(module));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.getModulePreview("mod-p", "class-B"))
                .isInstanceOf(com.pally.shared.exception.BusinessException.class);
        verify(itemRepository, never()).findByModuleIdOrderBySortOrder(anyString());
    }

    @Test
    void getModulePreview_returnsOrderedItems_withoutAnswerKeys() {
        LearningModule module = buildModule("mod-p", "LEARN");
        module.setClassId("class-A");
        when(moduleRepository.findById("mod-p")).thenReturn(java.util.Optional.of(module));
        ModuleContentItem item = new ModuleContentItem();
        item.setId("i1");
        item.setStage("LEARN");
        item.setType("MICRO_CARD");
        item.setContentJson("{\"title\":\"x\"}");
        item.setAnswerJson("{\"secret\":\"answer-key\"}");
        item.setSortOrder(0);
        when(itemRepository.findByModuleIdOrderBySortOrder("mod-p")).thenReturn(List.of(item));

        List<Map<String, Object>> out = service.getModulePreview("mod-p", "class-A");

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsKeys("id", "stage", "type", "contentJson", "sortOrder");
        // A preview must NEVER leak gradeable answer keys.
        assertThat(out.get(0)).doesNotContainKey("answerJson");
        assertThat(out.get(0).get("contentJson")).isEqualTo("{\"title\":\"x\"}");
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
