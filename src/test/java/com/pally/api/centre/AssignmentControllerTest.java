package com.pally.api.centre;

import com.pally.domain.assignment.AssignmentService;
import com.pally.domain.centre.CentreAccessService;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentControllerTest {

    @Mock private CentreAccessService accessService;
    @Mock private AssignmentService assignmentService;
    @Mock private OrgClassJpaRepository classRepo;

    private AssignmentController controller;

    private static final String OWNER_ID = "owner-1";
    private static final String ORG_ID = "org-1";
    private static final String CLASS_ID = "class-1";

    @BeforeEach
    void setUp() {
        controller = new AssignmentController(accessService, assignmentService, classRepo);
    }

    private void stubClassAndOwner() {
        OrgClassJpaEntity cls = new OrgClassJpaEntity();
        cls.setId(CLASS_ID);
        cls.setOrganizationId(ORG_ID);
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(new OrganizationJpaEntity());
    }

    @Test
    void createAssignment_201_returnsCreatedAssignment() {
        stubClassAndOwner();
        AssignmentJpaEntity entity = new AssignmentJpaEntity();
        entity.setId("assign-1");
        entity.setClassId(CLASS_ID);
        entity.setTitle("Homework 1");
        entity.setType("PRE_CLASS");
        entity.setStages("LEARN,TEST,PROVE");
        entity.setDueDate(Instant.now().plus(7, ChronoUnit.DAYS));
        entity.setCreatedBy(OWNER_ID);
        entity.setCreatedAt(Instant.now());

        when(assignmentService.create(
                eq(CLASS_ID), eq("Homework 1"), eq("PRE_CLASS"),
                any(), any(), any(), any(), any(), eq(OWNER_ID)))
                .thenReturn(entity);

        Instant due = Instant.now().plus(7, ChronoUnit.DAYS);
        Map<String, Object> body = Map.of(
                "title", "Homework 1",
                "type", "PRE_CLASS",
                "moduleIds", List.of("mod-1"),
                "dueDate", due.toString());

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.createAssignment(OWNER_ID, ORG_ID, CLASS_ID, body);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().get("title")).isEqualTo("Homework 1");
    }

    @Test
    void listAssignments_200_returnsList() {
        stubClassAndOwner();
        AssignmentJpaEntity a1 = new AssignmentJpaEntity();
        a1.setId("a1");
        a1.setClassId(CLASS_ID);
        a1.setTitle("HW1");
        a1.setType("PRE_CLASS");
        a1.setDueDate(Instant.now());
        a1.setCreatedBy(OWNER_ID);
        a1.setCreatedAt(Instant.now());

        when(assignmentService.listForClass(CLASS_ID)).thenReturn(List.of(a1));

        ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
                controller.listAssignments(OWNER_ID, ORG_ID, CLASS_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data()).hasSize(1);
    }

    @Test
    void getAssignment_200_returnsDetail() {
        stubClassAndOwner();
        Map<String, Object> detail = Map.of("id", "a1", "title", "HW1", "completedCount", 3);
        when(assignmentService.getDetail("a1")).thenReturn(detail);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.getAssignment(OWNER_ID, ORG_ID, CLASS_ID, "a1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data().get("completedCount")).isEqualTo(3);
    }

    @Test
    void deleteAssignment_200_delegatesToService() {
        stubClassAndOwner();
        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.deleteAssignment(OWNER_ID, ORG_ID, CLASS_ID, "a1");

        verify(assignmentService).delete("a1");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void createAssignment_wrongOrg_throws403() {
        when(accessService.ensureOwner("other-user", ORG_ID))
                .thenThrow(new BusinessException("Not owner", 403));

        assertThatThrownBy(() -> controller.createAssignment(
                "other-user", ORG_ID, CLASS_ID, Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Not owner");
    }

    @Test
    void createAssignment_classNotFound_throws404() {
        when(classRepo.findById("bad-class")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.createAssignment(
                OWNER_ID, ORG_ID, "bad-class", Map.of("title", "T", "type", "PRE_CLASS",
                        "dueDate", Instant.now().toString())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Class not found");
    }
}
