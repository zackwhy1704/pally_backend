package com.pally.domain.centre;

import com.pally.domain.module.ModuleService;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ContentReviewService}.
 * Uses mocked domain ports — zero JPA or Spring context required.
 */
@ExtendWith(MockitoExtension.class)
class ContentReviewServiceTest {

    private static final String USER    = "teacher-1";
    private static final String ORG_ID  = "org-1";
    private static final String CLASS_ID = "class-1";
    private static final String ITEM_ID  = "item-1";

    @Mock CentreAccessService centreAccessService;
    @Mock OrgClassRepository orgClassRepository;
    @Mock ContentReviewPort reviewPort;
    @Mock ModuleService moduleService;
    @Mock com.pally.domain.content.OutputValidator outputValidator;

    @InjectMocks ContentReviewService service;

    // ── listDraftContent ─────────────────────────────────────────────────────

    @Test
    void listDraftContent_happyPath_returnsDraftItems() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID))
                .thenReturn(Optional.of(ORG_ID));
        List<Map<String, Object>> drafts = List.of(Map.of("id", ITEM_ID, "status", "DRAFT"));
        when(reviewPort.findDraftItemsByClass(CLASS_ID)).thenReturn(drafts);

        List<Map<String, Object>> result = service.listDraftContent(USER, ORG_ID, CLASS_ID);

        assertThat(result).isEqualTo(drafts);
        verify(centreAccessService).ensureStaff(USER, ORG_ID);
    }

    @Test
    void listDraftContent_classNotInOrg_throws403() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID))
                .thenReturn(Optional.of("other-org"));

        assertThatThrownBy(() -> service.listDraftContent(USER, ORG_ID, CLASS_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);

        verify(reviewPort, never()).findDraftItemsByClass(CLASS_ID);
    }

    @Test
    void listDraftContent_classNotFound_throws404() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listDraftContent(USER, ORG_ID, CLASS_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);

        verify(reviewPort, never()).findDraftItemsByClass(CLASS_ID);
    }

    // ── updateContentItem ────────────────────────────────────────────────────

    @Test
    void updateContentItem_approve_returnsUpdatedDto() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID))
                .thenReturn(Optional.of(ORG_ID));
        ContentReviewPort.ContentItemView view = new ContentReviewPort.ContentItemView(
                ITEM_ID, "mod-1", "LEARN", "FLASHCARD", "{}", null, 0, "APPROVED", null);
        when(reviewPort.updateItem(any())).thenReturn(view);

        Map<String, Object> result = service.updateContentItem(
                USER, ORG_ID, CLASS_ID, ITEM_ID, Map.of("status", "APPROVED"));

        assertThat(result.get("status")).isEqualTo("APPROVED");
        assertThat(result.get("itemId")).isEqualTo(ITEM_ID);
    }

    @Test
    void updateContentItem_passesClassIdIntoCommand_soAdapterCanScope() {
        // Cross-tenant defence: the service must hand the verified classId to the
        // port so the adapter can reject items belonging to another class.
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID))
                .thenReturn(Optional.of(ORG_ID));
        ContentReviewPort.ContentItemView view = new ContentReviewPort.ContentItemView(
                ITEM_ID, "mod-1", "LEARN", "FLASHCARD", "{}", null, 0, "APPROVED", null);
        when(reviewPort.updateItem(any())).thenReturn(view);

        service.updateContentItem(USER, ORG_ID, CLASS_ID, ITEM_ID, Map.of("status", "APPROVED"));

        ArgumentCaptor<ContentReviewPort.UpdateItemCommand> captor =
                ArgumentCaptor.forClass(ContentReviewPort.UpdateItemCommand.class);
        verify(reviewPort).updateItem(captor.capture());
        assertThat(captor.getValue().classId()).isEqualTo(CLASS_ID);
        assertThat(captor.getValue().itemId()).isEqualTo(ITEM_ID);
    }

    @Test
    void updateContentItem_reject_returnsDeletion() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID))
                .thenReturn(Optional.of(ORG_ID));
        when(reviewPort.updateItem(any())).thenReturn(null); // null means deleted

        Map<String, Object> result = service.updateContentItem(
                USER, ORG_ID, CLASS_ID, ITEM_ID, Map.of("status", "REJECTED"));

        assertThat(result.get("deleted")).isEqualTo(true);
    }

    @Test
    void updateContentItem_invalidStatus_throws400() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID))
                .thenReturn(Optional.of(ORG_ID));

        assertThatThrownBy(() -> service.updateContentItem(
                USER, ORG_ID, CLASS_ID, ITEM_ID, Map.of("status", "NONSENSE")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);

        verify(reviewPort, never()).updateItem(any());
    }

    // ── approveAll ───────────────────────────────────────────────────────────

    @Test
    void approveAll_allValid_approvesEverySubmittedId() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID))
                .thenReturn(Optional.of(ORG_ID));
        when(reviewPort.findDraftItemsByClass(CLASS_ID)).thenReturn(List.of(
                Map.of("itemId", "a", "type", "MICRO_CARD", "contentJson", "{}"),
                Map.of("itemId", "b", "type", "MICRO_CARD", "contentJson", "{}")));
        when(outputValidator.explain(any(), any())).thenReturn(Optional.empty()); // all valid
        when(reviewPort.approveItems(eq(CLASS_ID), any())).thenReturn(2);

        Map<String, Object> result = service.approveAll(USER, ORG_ID, CLASS_ID);

        assertThat(result.get("approvedCount")).isEqualTo(2);
        assertThat(result.get("skippedCount")).isEqualTo(0);
        // only the valid ids are handed to the port (never a bare flip-all)
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(reviewPort).approveItems(eq(CLASS_ID), ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void approveAll_skipsInvalidDrafts_andReportsReasonToTeacher() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID))
                .thenReturn(Optional.of(ORG_ID));
        when(reviewPort.findDraftItemsByClass(CLASS_ID)).thenReturn(List.of(
                Map.of("itemId", "good", "type", "MICRO_CARD", "contentJson", "{}",
                        "moduleTitle", "Fractions"),
                Map.of("itemId", "blank", "type", "SPOT_MISTAKE", "contentJson", "{}",
                        "moduleTitle", "Decimals")));
        // "blank" is invalid, "good" is valid.
        when(outputValidator.explain(any(), any()))
                .thenReturn(Optional.empty())               // good
                .thenReturn(Optional.of("a spot-the-mistake needs a problem")); // blank
        when(reviewPort.approveItems(eq(CLASS_ID), any())).thenReturn(1);

        Map<String, Object> result = service.approveAll(USER, ORG_ID, CLASS_ID);

        assertThat(result.get("approvedCount")).isEqualTo(1);
        assertThat(result.get("skippedCount")).isEqualTo(1);
        // only the VALID id was submitted for approval — the blank never flips live.
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(reviewPort).approveItems(eq(CLASS_ID), ids.capture());
        assertThat(ids.getValue()).containsExactly("good");
        // the teacher gets the reason, not a silent skip.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) result.get("skipped");
        assertThat(skipped).hasSize(1);
        assertThat(skipped.get(0)).containsEntry("itemId", "blank")
                .containsEntry("moduleTitle", "Decimals");
        assertThat(skipped.get(0).get("reason").toString()).contains("spot-the-mistake");
    }

    @Test
    void updateContentItem_approveIntoServable_whenInvalid_refusedWith422() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID))
                .thenReturn(Optional.of(ORG_ID));
        // existing DRAFT spot-mistake with blank content; teacher tries to approve it LIVE.
        when(reviewPort.findById(ITEM_ID)).thenReturn(new ContentReviewPort.ContentItemView(
                ITEM_ID, "mod-1", "TEST", "SPOT_MISTAKE", "{}", "{}", 0, "DRAFT", null));
        when(outputValidator.explain(any(), any()))
                .thenReturn(Optional.of("a spot-the-mistake needs a problem"));

        assertThatThrownBy(() -> service.updateContentItem(
                USER, ORG_ID, CLASS_ID, ITEM_ID, Map.of("status", "LIVE")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 422)
                .hasMessageContaining("spot-the-mistake");

        verify(reviewPort, never()).updateItem(any()); // the blank never flips live
    }

    @Test
    void updateContentItem_editLiveItemIntoBlank_refusedWith422_theEditDoor() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID))
                .thenReturn(Optional.of(ORG_ID));
        // Already-LIVE item; teacher edits its content to blankness (no status change).
        when(reviewPort.findById(ITEM_ID)).thenReturn(new ContentReviewPort.ContentItemView(
                ITEM_ID, "mod-1", "LEARN", "MICRO_CARD", "{\"title\":\"x\",\"body\":\"y\"}",
                null, 0, "LIVE", null));
        when(outputValidator.explain(any(), any()))
                .thenReturn(Optional.of("a card needs a non-empty title and body"));

        assertThatThrownBy(() -> service.updateContentItem(
                USER, ORG_ID, CLASS_ID, ITEM_ID,
                Map.of("contentJson", "{\"title\":\"\",\"body\":\"\"}")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 422);

        verify(reviewPort, never()).updateItem(any());
    }

    // ── examReadiness ────────────────────────────────────────────────────────

    @Test
    void examReadiness_happyPath_delegatesToModuleService() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID))
                .thenReturn(Optional.of(ORG_ID));
        Map<String, Object> readiness = Map.of("ready", true);
        when(moduleService.getClassExamReadiness(CLASS_ID)).thenReturn(readiness);

        Map<String, Object> result = service.examReadiness(USER, ORG_ID, CLASS_ID);

        assertThat(result).isEqualTo(readiness);
    }

    // ── Module preview (teacher-scoped, read-only) ─────────────────────────────

    @Test
    void previewModule_authorized_delegatesToModuleService() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID)).thenReturn(Optional.of(ORG_ID));
        List<Map<String, Object>> items = List.of(Map.of("id", "i1", "stage", "LEARN"));
        when(moduleService.getModulePreview("mod-1", CLASS_ID)).thenReturn(items);

        List<Map<String, Object>> result = service.previewModule(USER, ORG_ID, CLASS_ID, "mod-1");

        assertThat(result).isEqualTo(items);
        verify(centreAccessService).ensureStaff(USER, ORG_ID); // reused auth, not hand-rolled
    }

    @Test
    void previewModule_nonStaff_denied_neverFetchesModule() {
        doThrow(new BusinessException("Forbidden", 403))
                .when(centreAccessService).ensureStaff(USER, ORG_ID);

        assertThatThrownBy(() -> service.previewModule(USER, ORG_ID, CLASS_ID, "mod-1"))
                .isInstanceOf(BusinessException.class);
        verify(moduleService, never()).getModulePreview(any(), any());
    }
}
