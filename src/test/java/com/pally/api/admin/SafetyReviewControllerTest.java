package com.pally.api.admin;

import com.pally.infrastructure.persistence.safety.ChatSafetyFlagJpaEntity;
import com.pally.infrastructure.persistence.safety.ChatSafetyFlagJpaRepository;
import com.pally.shared.AdminSecretGuard;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SafetyReviewControllerTest {

    @Mock AdminSecretGuard adminSecretGuard;
    @Mock ChatSafetyFlagJpaRepository flagRepo;

    @InjectMocks SafetyReviewController controller;

    private static final String VALID_SECRET  = "correct";
    private static final String CHILD_USER_ID = "user-abc";

    private ChatSafetyFlagJpaEntity flag(String id, String category, String severity) {
        ChatSafetyFlagJpaEntity e = new ChatSafetyFlagJpaEntity();
        e.setId(id);
        e.setChildUserId(CHILD_USER_ID);
        e.setCategory(category);
        e.setSeverity(severity);
        e.setSnippet("test snippet");
        e.setSource("INPUT");
        e.setCreatedAt(Instant.now());
        e.setMessageId("msg-1");
        e.setAvatarId("avatar-1");
        return e;
    }

    @Test
    void validSecret_returnsFlagsForUser() {
        doNothing().when(adminSecretGuard).require(VALID_SECRET);
        when(flagRepo.findByChildUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq(CHILD_USER_ID), any(Instant.class)))
                .thenReturn(List.of(flag("f-1", "SELF_HARM", "HIGH")));

        ResponseEntity<ApiResponse<List<SafetyFlagDto>>> response =
                controller.getSafetyFlags(VALID_SECRET, CHILD_USER_ID, 24);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<SafetyFlagDto> dtos = response.getBody().data();
        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).id()).isEqualTo("f-1");
        assertThat(dtos.get(0).category()).isEqualTo("SELF_HARM");
        assertThat(dtos.get(0).severity()).isEqualTo("HIGH");
        assertThat(dtos.get(0).snippet()).isEqualTo("test snippet");
    }

    @Test
    void missingSecret_throws403() {
        doThrow(new BusinessException("Admin access required", 403))
                .when(adminSecretGuard).require(null);

        assertThatThrownBy(() -> controller.getSafetyFlags(null, CHILD_USER_ID, 24))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Admin access required");
    }

    @Test
    void wrongSecret_throws403() {
        doThrow(new BusinessException("Admin access required", 403))
                .when(adminSecretGuard).require("wrong");

        assertThatThrownBy(() -> controller.getSafetyFlags("wrong", CHILD_USER_ID, 24))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void sinceHours_passedCorrectly_toRepository() {
        doNothing().when(adminSecretGuard).require(VALID_SECRET);
        when(flagRepo.findByChildUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq(CHILD_USER_ID), any(Instant.class)))
                .thenReturn(List.of());

        controller.getSafetyFlags(VALID_SECRET, CHILD_USER_ID, 48);

        verify(flagRepo).findByChildUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq(CHILD_USER_ID), any(Instant.class));
    }

    @Test
    void noFlags_returnsEmptyList() {
        doNothing().when(adminSecretGuard).require(VALID_SECRET);
        when(flagRepo.findByChildUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                eq(CHILD_USER_ID), any(Instant.class)))
                .thenReturn(List.of());

        ResponseEntity<ApiResponse<List<SafetyFlagDto>>> response =
                controller.getSafetyFlags(VALID_SECRET, CHILD_USER_ID, 24);

        assertThat(response.getBody().data()).isEmpty();
    }
}
