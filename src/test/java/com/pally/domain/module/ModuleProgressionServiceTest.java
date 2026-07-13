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
                avatarRepository, centreAccessService, weaknessProfileService,
                new GradingWeights());
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
        when(itemRepository.findServableByModuleIdAndStageOrderBySortOrder("mod-c", "LEARN"))
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
        when(itemRepository.findServableByModuleIdAndStageOrderBySortOrder("mod-s", "LEARN"))
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

    // ── module PLAYABILITY invariant: a stage never enters empty-served ──
    // The stuck-TEST prod bug (2026-07-13): a stage whose items were all made
    // non-servable (reaper-QUARANTINED / DRAFT) served `200 + items:[]`, so the
    // student had nothing to answer and TEST could never advance — a dead-end.

    private ModuleContentItem item(String id, String stage, String type) {
        ModuleContentItem it = new ModuleContentItem();
        it.setId(id);
        it.setStage(stage);
        it.setType(type);
        it.setContentJson("{\"statement\":\"s\",\"isTrue\":true,\"explanation\":\"e\"}");
        it.setSortOrder(0);
        return it;
    }

    @Test
    void startModule_testStageItemsExistButNoneServable_returnsContentUpdating_notDeadEnd() {
        // The exact prod shape: TEST items exist (unfiltered count > 0) but all are
        // non-servable → the servable list is empty. Must NOT be a benign 200.
        LearningModule module = buildModule("mod-stuck", "TEST");
        when(moduleRepository.findById("mod-stuck")).thenReturn(Optional.of(module));
        when(itemRepository.findServableByModuleIdAndStageOrderBySortOrder("mod-stuck", "TEST"))
                .thenReturn(List.of());
        when(itemRepository.countByModuleIdAndStage("mod-stuck", "TEST")).thenReturn(3);

        Map<String, Object> result = service.startModule("mod-stuck", "user-1");

        assertThat(result.get("items")).asList().isEmpty();
        assertThat(result.get("contentStatus")).isEqualTo("CONTENT_UPDATING");
        assertThat(result.get("stage")).isEqualTo("TEST");
    }

    @Test
    void startModule_stageGenuinelyHasNoItems_returnsContentUnavailable() {
        LearningModule module = buildModule("mod-empty", "TEST");
        when(moduleRepository.findById("mod-empty")).thenReturn(Optional.of(module));
        when(itemRepository.findServableByModuleIdAndStageOrderBySortOrder("mod-empty", "TEST"))
                .thenReturn(List.of());
        when(itemRepository.countByModuleIdAndStage("mod-empty", "TEST")).thenReturn(0);

        Map<String, Object> result = service.startModule("mod-empty", "user-1");

        assertThat(result.get("contentStatus")).isEqualTo("CONTENT_UNAVAILABLE");
    }

    @Test
    void startModule_normalServableStage_carriesNoContentStatus() {
        // Playability holds → the field is ABSENT (normal payload shape unchanged).
        LearningModule module = buildModule("mod-ok", "TEST");
        when(moduleRepository.findById("mod-ok")).thenReturn(Optional.of(module));
        when(itemRepository.findServableByModuleIdAndStageOrderBySortOrder("mod-ok", "TEST"))
                .thenReturn(List.of(item("i-1", "TEST", "HOT_TAKE")));

        Map<String, Object> result = service.startModule("mod-ok", "user-1");

        assertThat(result).doesNotContainKey("contentStatus");
        assertThat(result.get("items")).asList().hasSize(1);
    }

    @Test
    void playabilityWalk_everyEnteredStageServesAtLeastOneRenderableItem() {
        // The named invariant this whole failure exists to buy: LEARN → TEST →
        // PROVE each serve ≥1 item and carry no CONTENT_UPDATING dead-end signal.
        record Step(String stage, String type) {}
        for (Step step : List.of(
                new Step("LEARN", "MICRO_CARD"),
                new Step("TEST", "SPOT_MISTAKE"),
                new Step("PROVE", "PROVE_QUESTION"))) {
            String id = "walk-" + step.stage();
            LearningModule module = buildModule(id, step.stage());
            when(moduleRepository.findById(id)).thenReturn(Optional.of(module));
            // PROVE has a pre-serve existence gate — a positive count skips regen.
            if ("PROVE".equals(step.stage())) {
                when(itemRepository.countByModuleIdAndStage(id, "PROVE")).thenReturn(1);
            }
            when(itemRepository.findServableByModuleIdAndStageOrderBySortOrder(id, step.stage()))
                    .thenReturn(List.of(item("it-" + step.stage(), step.stage(), step.type())));

            Map<String, Object> result = service.startModule(id, "user-1");

            assertThat(result.get("stage")).isEqualTo(step.stage());
            assertThat(result.get("items")).as("stage %s must serve ≥1 item", step.stage())
                    .asList().isNotEmpty();
            assertThat(result).as("stage %s must not dead-end", step.stage())
                    .doesNotContainKey("contentStatus");
        }
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
        when(itemRepository.findServableByModuleIdAndStageOrderBySortOrder("mod-1", "PROVE"))
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
        when(itemRepository.findServableByModuleIdAndStageOrderBySortOrder("mod-1", "PROVE"))
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
                .thenReturn(ModuleProveEvaluator.ProveResult.ok(
                        true, List.of("kp1"), List.of(), "Great!", 0.85));

        when(itemRepository.countByModuleIdAndStage("mod-1", "PROVE")).thenReturn(2);
        when(progressRepository.countByModuleIdAndUserIdAndStage("mod-1", "user-1", "PROVE"))
                .thenReturn(1);

        Map<String, Object> result = service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("itemId", "item-1", "response", "My answer")));

        // Tier 2: open-ended PROVE never returns a system-asserted score. It returns
        // feedback + the reference answer + a self-assess prompt; the LLM score is
        // NOT persisted (mastery waits for the student's self-report).
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("score")).isNull();
        assertThat(results.get(0).get("graded")).isEqualTo(false);
        assertThat(results.get(0).get("selfAssess")).isEqualTo(true);
        assertThat(results.get(0).get("feedback")).isEqualTo("Great!");
        // The persisted row is UNGRADED (no false 0 in mastery).
        org.mockito.ArgumentCaptor<ModuleProgress> cap =
                org.mockito.ArgumentCaptor.forClass(ModuleProgress.class);
        verify(progressRepository).save(cap.capture());
        assertThat(cap.getValue().getScore()).isNull();
        assertThat(cap.getValue().getSignalType()).isEqualTo(GradingSignal.UNGRADED);
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
    void selfReport_strongYes_nudgesWikiCertainty_andRecordsSelfReportSignal() {
        // The certainty nudge moved OFF the PROVE submit path (which no longer
        // asserts) onto the student's self-report.
        LearningModule module = buildModule("mod-1", "PROVE");
        module.setAvatarId("avatar-1");
        module.setWikiPageSlug("photosynthesis");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");
        item.setStage("PROVE");
        item.setType("PROVE_QUESTION");
        item.setAnswerJson("{\"targetConcept\":\"C\"}");
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));

        ModuleProgress existing = new ModuleProgress();
        existing.setModuleId("mod-1");
        existing.setUserId("user-1");
        existing.setItemId("item-1");
        existing.setStage("PROVE");
        existing.setSignalType(GradingSignal.UNGRADED);
        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "item-1"))
                .thenReturn(Optional.of(existing));
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.submitSelfReport("mod-1", "user-1", "item-1", SelfReport.YES);

        // SELF_REPORT YES (score 1.0) is a strong signal → +0.05 certainty.
        assertThat(existing.getSignalType()).isEqualTo(GradingSignal.SELF_REPORT);
        assertThat(existing.getScore()).isEqualByComparingTo(BigDecimal.ONE);

        verify(wikiRepository).adjustCertainty("avatar-1", List.of("photosynthesis"), 0.05);
    }

    // ── grading-harness hardening ───────────────────────────────────────

    @Test
    void submitAnswers_testStage_ignoresClientScore_persistsUngraded_spoofClosed() {
        // The client-authoritative grading hole: a tampered client posts an
        // all-correct score. The server must IGNORE it (never persist a client
        // score) — the row is UNGRADED (score NULL), asserting nothing.
        LearningModule module = buildModule("mod-1", "TEST");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");
        item.setStage("TEST");
        item.setType("HOT_TAKE");
        item.setAnswerJson("{\"isTrue\":true}");
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));
        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "item-1"))
                .thenReturn(Optional.empty());
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.countByModuleIdAndStage("mod-1", "TEST")).thenReturn(2);
        when(progressRepository.countByModuleIdAndUserIdAndStage("mod-1", "user-1", "TEST"))
                .thenReturn(1);

        service.submitAnswers("mod-1", "user-1", List.of(Map.of(
                "itemId", "item-1", "response", "{\"score\":1.0,\"concept\":\"C\"}")));

        org.mockito.ArgumentCaptor<ModuleProgress> cap =
                org.mockito.ArgumentCaptor.forClass(ModuleProgress.class);
        verify(progressRepository).save(cap.capture());
        assertThat(cap.getValue().getScore()).isNull();            // client 1.0 IGNORED
        assertThat(cap.getValue().getSignalType()).isEqualTo(GradingSignal.UNGRADED);
    }

    @Test
    void proveStageCompletes_withOnlyUngradedItems_leavesMasteryUnmoved_neverFalseZero() {
        // Fail-open regression: a PROVE stage that completes with no trustworthy
        // signal (student hasn't self-assessed) must NOT set mastery to 0 — it
        // must leave it unchanged (null), so an un-self-assessed module is never
        // recorded as a 0% failure.
        LearningModule module = buildModule("mod-1", "PROVE");
        module.setAvatarId("avatar-1");
        module.setMasteryPct(null);
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");
        item.setStage("PROVE");
        item.setType("PROVE_QUESTION");
        item.setContentJson("{\"question\":\"Q\",\"targetConcept\":\"C\"}");
        item.setAnswerJson("{\"targetConcept\":\"C\"}");
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));
        when(proveEvaluator.evaluateAnswer(eq(item), any()))
                .thenReturn(ModuleProveEvaluator.ProveResult.ungraded("eval down"));
        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "item-1"))
                .thenReturn(Optional.empty());

        ModuleProgress saved = new ModuleProgress();
        saved.setStage("PROVE");
        saved.setSignalType(GradingSignal.UNGRADED);
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // PROVE stage completes (this was the last item) → updateMastery runs.
        when(itemRepository.countByModuleIdAndStage("mod-1", "PROVE")).thenReturn(1);
        when(progressRepository.countByModuleIdAndUserIdAndStage("mod-1", "user-1", "PROVE"))
                .thenReturn(1);
        when(progressRepository.findByModuleIdAndUserId("mod-1", "user-1"))
                .thenReturn(List.of(saved));
        when(weaknessProfileService.isEnabled()).thenReturn(false);

        service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("itemId", "item-1", "response", "short")));

        // Mastery left UNCHANGED (null) — not a false 0.
        assertThat(module.getMasteryPct()).isNull();
    }

    @Test
    void selfReport_onCompletedModule_appliesSelfReportWeightToMastery() {
        // SELF_REPORT YES (1.0) at weight 0.25 → 25% mastery (a deterministic 1.0
        // would be 100%). The trust weight scales the mastery magnitude.
        LearningModule module = buildModule("mod-1", "COMPLETE");
        module.setAvatarId("avatar-1");
        module.setWikiPageSlug("photosynthesis");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");
        item.setStage("PROVE");
        item.setType("PROVE_QUESTION");
        item.setAnswerJson("{\"targetConcept\":\"C\"}");
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));

        ModuleProgress p = new ModuleProgress();
        p.setModuleId("mod-1");
        p.setUserId("user-1");
        p.setItemId("item-1");
        p.setStage("PROVE");
        p.setSignalType(GradingSignal.UNGRADED);
        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "item-1"))
                .thenReturn(Optional.of(p));
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(progressRepository.findByModuleIdAndUserId("mod-1", "user-1"))
                .thenReturn(List.of(p));
        when(weaknessProfileService.isEnabled()).thenReturn(false);

        service.submitSelfReport("mod-1", "user-1", "item-1", SelfReport.YES);

        // SELF_REPORT weight 0.30 x YES(1.0) x 100 = 30.00.
        assertThat(module.getMasteryPct()).isEqualByComparingTo(new BigDecimal("30.00"));
    }

    private void stubHotTakeSubmit(ModuleContentItem item) {
        when(moduleRepository.findById("mod-1"))
                .thenReturn(Optional.of(buildModule("mod-1", "TEST")));
        when(itemRepository.findById("item-1")).thenReturn(Optional.of(item));
        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "item-1"))
                .thenReturn(Optional.empty());
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.countByModuleIdAndStage("mod-1", "TEST")).thenReturn(2);
        when(progressRepository.countByModuleIdAndUserIdAndStage("mod-1", "user-1", "TEST"))
                .thenReturn(1);
    }

    private ModuleContentItem hotTake(String answerJson) {
        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");
        item.setStage("TEST");
        item.setType("HOT_TAKE");
        item.setAnswerJson(answerJson);
        return item;
    }

    private ModuleProgress submitHotTake(String rawChoice) {
        service.submitAnswers("mod-1", "user-1",
                List.of(Map.of("itemId", "item-1", "response", rawChoice)));
        org.mockito.ArgumentCaptor<ModuleProgress> cap =
                org.mockito.ArgumentCaptor.forClass(ModuleProgress.class);
        verify(progressRepository).save(cap.capture());
        return cap.getValue();
    }

    @Test
    void hotTake_agreeMatchesTrueKey_gradedDeterministicCorrect() {
        stubHotTakeSubmit(hotTake("{\"isTrue\":true}"));
        ModuleProgress saved = submitHotTake("AGREE");
        assertThat(saved.getSignalType()).isEqualTo(GradingSignal.DETERMINISTIC);
        assertThat(saved.getScore()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void hotTake_disagreeAgainstTrueKey_gradedDeterministicIncorrect() {
        stubHotTakeSubmit(hotTake("{\"isTrue\":true}"));
        ModuleProgress saved = submitHotTake("DISAGREE");
        assertThat(saved.getSignalType()).isEqualTo(GradingSignal.DETERMINISTIC);
        assertThat(saved.getScore()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void hotTake_oldClientScorePayload_noRawChoice_degradesToUngraded_not400() {
        stubHotTakeSubmit(hotTake("{\"isTrue\":true}"));
        // Legacy client posted a computed score, not a raw AGREE/DISAGREE choice.
        ModuleProgress saved = submitHotTake("{\"score\":1.0}");
        assertThat(saved.getSignalType()).isEqualTo(GradingSignal.UNGRADED);
        assertThat(saved.getScore()).isNull();
    }

    @Test
    void hotTake_noPersistedKey_degradesToUngraded_neverGuesses() {
        stubHotTakeSubmit(hotTake("{}")); // no isTrue key
        ModuleProgress saved = submitHotTake("AGREE");
        assertThat(saved.getSignalType()).isEqualTo(GradingSignal.UNGRADED);
        assertThat(saved.getScore()).isNull();
    }

    @Test
    void mastery_blendsTestDeterministicAndProveSelfReport_atTheirWeights() {
        // A correct HOT_TAKE (DETERMINISTIC 1.0) + a PROVE self-report YES (0.30)
        // → (1.0*1.0 + 0.30*1.0) / 2 = 0.65 → 65.00. Proves TEST deterministic
        // signal now feeds module mastery.
        LearningModule module = buildModule("mod-1", "COMPLETE");
        module.setAvatarId("avatar-1");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleContentItem proveItem = new ModuleContentItem();
        proveItem.setId("prove-1");
        proveItem.setStage("PROVE");
        proveItem.setType("PROVE_QUESTION");
        proveItem.setAnswerJson("{\"targetConcept\":\"C\"}");
        when(itemRepository.findById("prove-1")).thenReturn(Optional.of(proveItem));

        ModuleProgress testDet = new ModuleProgress();
        testDet.setStage("TEST");
        testDet.setScore(BigDecimal.ONE);
        testDet.setSignalType(GradingSignal.DETERMINISTIC);
        ModuleProgress prove = new ModuleProgress();
        prove.setModuleId("mod-1");
        prove.setUserId("user-1");
        prove.setItemId("prove-1");
        prove.setStage("PROVE");
        prove.setSignalType(GradingSignal.UNGRADED);
        when(progressRepository.findByModuleIdAndUserIdAndItemId("mod-1", "user-1", "prove-1"))
                .thenReturn(Optional.of(prove));
        when(progressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(progressRepository.findByModuleIdAndUserId("mod-1", "user-1"))
                .thenReturn(List.of(testDet, prove));
        when(weaknessProfileService.isEnabled()).thenReturn(false);

        service.submitSelfReport("mod-1", "user-1", "prove-1", SelfReport.YES);

        assertThat(module.getMasteryPct()).isEqualByComparingTo(new BigDecimal("65.00"));
    }

    @Test
    void getResults_proveStageAllUngraded_reportsNullAverage_notFalseZero() {
        // R4 read-site fix: an all-UNGRADED PROVE stage must report averageScore
        // null ("not assessed"), never 0.0 (which reads as 0% mastery).
        LearningModule module = buildModule("mod-1", "COMPLETE");
        when(moduleRepository.findById("mod-1")).thenReturn(Optional.of(module));

        ModuleProgress p = new ModuleProgress();
        p.setModuleId("mod-1");
        p.setUserId("user-1");
        p.setItemId("item-1");
        p.setStage("PROVE");
        p.setScore(null);
        p.setSignalType(GradingSignal.UNGRADED);
        when(progressRepository.findByModuleIdAndUserId("mod-1", "user-1"))
                .thenReturn(List.of(p));
        when(itemRepository.countByModuleIdAndStage(eq("mod-1"), anyString()))
                .thenReturn(1);

        Map<String, Object> results = service.getResults("mod-1", "user-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> stageSummary =
                (Map<String, Object>) results.get("stageSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> prove = (Map<String, Object>) stageSummary.get("PROVE");
        assertThat(prove.get("averageScore")).isNull();
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

    private ModuleContentItem servableItem(String id, String status) {
        ModuleContentItem i = new ModuleContentItem();
        i.setId(id);
        i.setStatus(status);
        i.setStage("LEARN");
        i.setType("MICRO_CARD");
        i.setContentJson("{\"title\":\"t\",\"body\":\"b\"}");
        i.setSortOrder(0);
        return i;
    }

    // ── 1a: serving filters status; gating/badge read the FULL set ──────────────

    @Test
    @SuppressWarnings("unchecked")
    void getModuleDetail_servesOnlyServableItems_whileBadgeSeesTheFullSet() {
        LearningModule module = buildModule("mod-s", "LEARN");
        module.setClassId("class-c");
        when(moduleRepository.findById("mod-s")).thenReturn(Optional.of(module));
        when(avatarRepository.existsByIdAndUserId("avatar-1", "u1")).thenReturn(true);
        ModuleContentItem live = servableItem("i-live", "LIVE");
        ModuleContentItem draft = servableItem("i-draft", "DRAFT");
        // SERVING reads servable-only; BADGE reads the full set (must see the DRAFT).
        when(itemRepository.findServableByModuleIdOrderBySortOrder("mod-s"))
                .thenReturn(List.of(live));
        when(itemRepository.findByModuleIdOrderBySortOrder("mod-s"))
                .thenReturn(List.of(live, draft));
        when(progressRepository.findByModuleIdAndUserId("mod-s", "u1")).thenReturn(List.of());

        Map<String, Object> detail = service.getModuleDetail("mod-s", "u1");

        List<Map<String, Object>> served = (List<Map<String, Object>>) detail.get("items");
        assertThat(served).hasSize(1); // the DRAFT item is NOT served to the student
        assertThat(served.get(0).get("id")).isEqualTo("i-live");
        // The badge still detects the DRAFT (reads the full set) → NOT reviewed.
        assertThat(detail.get("teacherReviewed")).isEqualTo(false);
    }

    @Test
    void startModule_proveStage_draftProveItemsExist_doesNotSpuriouslyRegenerate() {
        LearningModule module = buildModule("mod-pr", "PROVE");
        when(moduleRepository.findById("mod-pr")).thenReturn(Optional.of(module));
        when(avatarRepository.existsByIdAndUserId("avatar-1", "u1")).thenReturn(true);
        // The PROVE-existence gate counts the FULL set → sees the (DRAFT) prove items.
        when(itemRepository.countByModuleIdAndStage("mod-pr", "PROVE")).thenReturn(3);
        // Serving is servable-only → empty (all DRAFT) — but this must NOT trigger a regen.
        when(itemRepository.findServableByModuleIdAndStageOrderBySortOrder("mod-pr", "PROVE"))
                .thenReturn(List.of());

        service.startModule("mod-pr", "u1");

        // The trap: had the gate been filtered, count would be 0 → an LLM regeneration.
        // The gate stays unfiltered, so the generator is never touched.
        verifyNoInteractions(contentGenerator);
    }
}
