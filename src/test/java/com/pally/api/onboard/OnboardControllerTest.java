package com.pally.api.onboard;

import com.pally.api.onboard.dto.QuickOnboardRequest;
import com.pally.api.onboard.dto.QuickOnboardResponse;
import com.pally.domain.avatar.Subject;
import com.pally.domain.onboard.QuickOnboardService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardControllerTest {

    @Mock
    private QuickOnboardService quickOnboardService;

    @InjectMocks
    private OnboardController controller;

    @Test
    void quickOnboard_newUser_returns201_withTokenAndIds() {
        QuickOnboardRequest request = new QuickOnboardRequest(
                "kid@example.com", "password123", "Kid", Subject.MATHS, "primary 4", null);

        when(quickOnboardService.execute(
                eq("kid@example.com"), eq("password123"), eq("Kid"),
                eq(Subject.MATHS), eq("primary 4"), eq(null)))
                .thenReturn(new QuickOnboardService.QuickOnboardResult(
                        "jwt-token-123", "user-id-1", "avatar-id-1"));

        ResponseEntity<ApiResponse<QuickOnboardResponse>> result = controller.quickOnboard(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().data().token()).isEqualTo("jwt-token-123");
        assertThat(result.getBody().data().userId()).isEqualTo("user-id-1");
        assertThat(result.getBody().data().avatarId()).isEqualTo("avatar-id-1");
    }

    @Test
    void quickOnboard_existingUserCorrectPassword_returns201_withNewAvatar() {
        QuickOnboardRequest request = new QuickOnboardRequest(
                "existing@example.com", "password123", null, Subject.SCIENCE, null, null);

        when(quickOnboardService.execute(
                eq("existing@example.com"), eq("password123"), eq(null),
                eq(Subject.SCIENCE), eq(null), eq(null)))
                .thenReturn(new QuickOnboardService.QuickOnboardResult(
                        "jwt-existing", "user-existing", "avatar-new"));

        ResponseEntity<ApiResponse<QuickOnboardResponse>> result = controller.quickOnboard(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().data().userId()).isEqualTo("user-existing");
        assertThat(result.getBody().data().avatarId()).isEqualTo("avatar-new");
    }

    @Test
    void quickOnboard_wrongPassword_throwsBusiness401() {
        QuickOnboardRequest request = new QuickOnboardRequest(
                "existing@example.com", "wrong-pass", null, Subject.ENGLISH, null, null);

        when(quickOnboardService.execute(
                eq("existing@example.com"), eq("wrong-pass"), eq(null),
                eq(Subject.ENGLISH), eq(null), eq(null)))
                .thenThrow(new BusinessException("Invalid email or password", 401));

        assertThatThrownBy(() -> controller.quickOnboard(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid email or password");
    }
}
