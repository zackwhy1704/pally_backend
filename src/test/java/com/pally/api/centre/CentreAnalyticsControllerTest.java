package com.pally.api.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.centre.CentreAnalyticsService;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Thin unit tests for {@link CentreAnalyticsController}.
 *
 * <p>These tests verify routing, auth-guard delegation, and HTTP response wrapping only.
 * Business logic belongs in {@link com.pally.domain.centre.CentreAnalyticsServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class CentreAnalyticsControllerTest {

    @Mock CentreAccessService    accessService;
    @Mock CentreAnalyticsService analyticsService;
    @Mock MeterRegistry          meterRegistry;

    @InjectMocks CentreAnalyticsController controller;

    private static final String OWNER_ID = "owner-1";
    private static final String STAFF_ID = "staff-1";
    private static final String ORG_ID   = "org-1";

    private OrganizationJpaEntity org;

    @BeforeEach
    void setUp() {
        org = new OrganizationJpaEntity();
        org.setId(ORG_ID);
        org.setName("Test Centre");
        org.setOwnerUserId(OWNER_ID);

        // Owner satisfies both ensureStaff (for widened routes) and ensureOwner (costSummary).
        lenient().when(accessService.ensureStaff(OWNER_ID, ORG_ID)).thenReturn(org);
        lenient().when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org);
        // Staff member satisfies ensureStaff but NOT ensureOwner.
        lenient().when(accessService.ensureStaff(STAFF_ID, ORG_ID)).thenReturn(org);
        lenient().when(accessService.ensureOwner(STAFF_ID, ORG_ID))
                .thenThrow(new BusinessException("You don't have access to this organization", 403));
    }

    // ── Overview ──────────────────────────────────────────────────────────

    @Test
    void overview_owner_returns200AndDelegates() {
        Map<String, Object> serviceResult = Map.of("activeThisWeek", 3L, "totalStudents", 5L);
        when(analyticsService.overview(anyString(), any())).thenReturn(serviceResult);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.overview(OWNER_ID, ORG_ID, null);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody().data()).containsKey("totalStudents");
        verify(analyticsService).overview(ORG_ID, null);
    }

    @Test
    void overview_staffMember_returns200() {
        when(analyticsService.overview(anyString(), any())).thenReturn(Map.of("activeThisWeek", 2L));

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.overview(STAFF_ID, ORG_ID, null);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
    }

    @Test
    void overview_unrelatedUser_throws403() {
        when(accessService.ensureStaff("intruder", ORG_ID))
                .thenThrow(new BusinessException("You don't have access to this organization", 403));

        assertThatThrownBy(() -> controller.overview("intruder", ORG_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
    }

    // ── Cohorts ───────────────────────────────────────────────────────────

    @Test
    void classes_owner_returns200AndDelegates() {
        when(analyticsService.cohorts(ORG_ID)).thenReturn(List.of(
                Map.of("cohort", "Sec3A", "studentCount", 5, "avgGrasp", 0.72)));

        ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
                controller.classes(OWNER_ID, ORG_ID);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody().data()).hasSize(1);
        verify(analyticsService).cohorts(ORG_ID);
    }

    @Test
    void classes_staffMember_returns200() {
        when(analyticsService.cohorts(ORG_ID)).thenReturn(Collections.emptyList());

        ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
                controller.classes(STAFF_ID, ORG_ID);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
    }

    // ── Heatmap ───────────────────────────────────────────────────────────

    @Test
    void heatmap_owner_returns200AndDelegates() {
        Map<String, Object> heatmap = Map.of(
                "students", List.of(), "topics", List.of(),
                "cells", List.of(), "topicAverages", List.of(), "weakest", List.of());
        when(analyticsService.heatmap(anyString(), anyString())).thenReturn(heatmap);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.heatmap(OWNER_ID, ORG_ID, "Sec3A");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(analyticsService).heatmap(ORG_ID, "Sec3A");
    }

    @Test
    void heatmap_staffMember_returns200() {
        when(analyticsService.heatmap(anyString(), anyString())).thenReturn(Map.of());

        assertThat(controller.heatmap(STAFF_ID, ORG_ID, "Sec3A").getStatusCodeValue()).isEqualTo(200);
    }

    @Test
    void heatmap_unrelatedUser_throws403() {
        when(accessService.ensureStaff("intruder", ORG_ID))
                .thenThrow(new BusinessException("You don't have access to this organization", 403));

        assertThatThrownBy(() -> controller.heatmap("intruder", ORG_ID, "Sec3A"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
    }

    // ── Student progress ──────────────────────────────────────────────────

    @Test
    void studentProgress_owner_returns200AndDelegates() {
        String studentId = "student-a";
        Map<String, Object> progress = Map.of("studentId", studentId, "displayName", "Alice");
        when(analyticsService.studentProgress(ORG_ID, studentId)).thenReturn(progress);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.studentProgress(OWNER_ID, ORG_ID, studentId);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody().data().get("studentId")).isEqualTo(studentId);
        verify(analyticsService).studentProgress(ORG_ID, studentId);
    }

    @Test
    void studentProgress_staffMember_returns200() {
        when(analyticsService.studentProgress(ORG_ID, "s-1")).thenReturn(Map.of("studentId", "s-1"));

        assertThat(controller.studentProgress(STAFF_ID, ORG_ID, "s-1").getStatusCodeValue()).isEqualTo(200);
    }

    // ── Cost summary — owner-only ─────────────────────────────────────────

    @Test
    void costSummary_owner_returns200AndDelegates() {
        Map<String, Object> summary = Map.of(
                "totalCostCents", 0.0, "perStudentAvg", 0.0, "breakdown", List.of());
        when(analyticsService.costSummary(ORG_ID, meterRegistry)).thenReturn(summary);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.costSummary(OWNER_ID, ORG_ID);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat((Double) response.getBody().data().get("totalCostCents")).isEqualTo(0.0);
        verify(analyticsService).costSummary(ORG_ID, meterRegistry);
    }

    @Test
    void costSummary_staffMember_throws403() {
        assertThatThrownBy(() -> controller.costSummary(STAFF_ID, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
    }
}
