package com.pally.api.module;

import com.pally.domain.assignment.AssignmentService;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.module.ModuleService;
import com.pally.infrastructure.persistence.assignment.AssignmentCompletionJpaEntity;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentStudentControllerTest {

    @Mock private AssignmentService assignmentService;
    @Mock private ModuleService moduleService;
    @Mock private AvatarRepository avatarRepository;

    private AssignmentStudentController controller;

    private static final String USER_ID = "user-1";
    private static final String AVATAR_ID = "avatar-1";

    @BeforeEach
    void setUp() {
        controller = new AssignmentStudentController(assignmentService, moduleService, avatarRepository);
    }

    @Test
    void listAssignments_avatarWithClass_returnsList() {
        Avatar avatar = Avatar.create(USER_ID, "Test", Subject.MATHS, CharacterType.ZAP);
        avatar.setClassId("class-1");
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(avatar));

        Map<String, Object> a1 = Map.of("id", "assign-1", "title", "HW1");
        when(assignmentService.listForUser(USER_ID, "class-1")).thenReturn(List.of(a1));

        ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
                controller.listAssignments(USER_ID, AVATAR_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data()).hasSize(1);
    }

    @Test
    void listAssignments_avatarWithoutClass_returnsEmptyList() {
        Avatar avatar = Avatar.create(USER_ID, "Test", Subject.MATHS, CharacterType.ZAP);
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(avatar));

        ResponseEntity<ApiResponse<List<Map<String, Object>>>> response =
                controller.listAssignments(USER_ID, AVATAR_ID);

        assertThat(response.getBody().data()).isEmpty();
    }

    @Test
    void listAssignments_avatarNotFound_throws() {
        when(avatarRepository.findById("bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.listAssignments(USER_ID, "bad"))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void startAssignment_200_returnsStatus() {
        AssignmentCompletionJpaEntity completion = new AssignmentCompletionJpaEntity();
        completion.setStatus("IN_PROGRESS");
        completion.setStartedAt(Instant.now());
        when(assignmentService.startAssignment("assign-1", USER_ID)).thenReturn(completion);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.startAssignment(USER_ID, AVATAR_ID, "assign-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data().get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void examPrep_200_returnsAggregatedData() {
        Map<String, Object> prep = new LinkedHashMap<>();
        prep.put("testDate", "2026-07-15");
        prep.put("daysRemaining", 34L);
        prep.put("concepts", List.of());
        when(moduleService.getExamPrep(AVATAR_ID)).thenReturn(prep);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.examPrep(USER_ID, AVATAR_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().data().get("testDate")).isEqualTo("2026-07-15");
    }
}
