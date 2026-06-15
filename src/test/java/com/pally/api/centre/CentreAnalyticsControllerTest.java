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
    private static final String ORG_ID   = "org-1";

    private OrganizationJpaEntity org;

    @BeforeEach
    void setUp() {
        org = new OrganizationJpaEntity();
        org.setId(ORG_ID);
        org.setName("Test Centre");
        org.setOwnerUserId(OWNER_ID);

        lenient().when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org);
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
    void overview_nonOwner_throws403() {
        when(accessService.ensureOwner("intruder", ORG_ID))
                .thenThrow(new BusinessException("You don't own this organization", 403));

        assertThatThrownBy(() -> controller.overview("intruder", ORG_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("don't own");
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
    void classes_emptyCentre_returns200EmptyList() {
        when(analyticsService.cohorts(ORG_ID)).thenReturn(Collections.emptyList());

        ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
                controller.classes(OWNER_ID, ORG_ID);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody().data()).isEmpty();
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
    void heatmap_nonOwner_throws403() {
        when(accessService.ensureOwner("intruder", ORG_ID))
                .thenThrow(new BusinessException("You don't own this organization", 403));

        assertThatThrownBy(() -> controller.heatmap("intruder", ORG_ID, "Sec3A"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("don't own");
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

    // ── Cost summary ──────────────────────────────────────────────────────

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
}
