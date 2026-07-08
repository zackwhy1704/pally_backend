package com.pally.domain.onboard;

import com.pally.domain.account.AccountType;

import com.pally.domain.auth.dto.AuthResponse;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.infrastructure.auth.AuthService;
import com.pally.infrastructure.auth.DuplicateSignupNotifier;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuickOnboardServiceTest {

    @Mock private AuthService authService;
    @Mock private AvatarRepository avatarRepository;
    @Mock private DuplicateSignupNotifier duplicateSignupNotifier;

    private QuickOnboardService service;

    @BeforeEach
    void setUp() {
        service = new QuickOnboardService(authService, avatarRepository, duplicateSignupNotifier);
    }

    @Test
    void execute_newUser_registersAndCreatesAvatar() {
        when(authService.emailExists("kid@test.com")).thenReturn(false);
        when(authService.register("kid@test.com", "pass1234", "Kid", null, null, null))
                .thenReturn(new AuthResponse("user-1", "tok-1", true, false, AccountType.SOLO));
        when(avatarRepository.save(any(Avatar.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        QuickOnboardService.QuickOnboardResult result =
                service.execute("kid@test.com", "pass1234", "Kid", Subject.MATHS, "primary 4");

        assertThat(result.token()).isEqualTo("tok-1");
        assertThat(result.userId()).isEqualTo("user-1");
        assertThat(result.avatarId()).isNotNull();

        ArgumentCaptor<Avatar> captor = ArgumentCaptor.forClass(Avatar.class);
        verify(avatarRepository).save(captor.capture());
        Avatar avatar = captor.getValue();
        assertThat(avatar.getSubject()).isEqualTo(Subject.MATHS);
        assertThat(avatar.getCharacterType()).isEqualTo(CharacterType.MOCHI);
        assertThat(avatar.getName()).isEqualTo("Maths Mochi");
        assertThat(avatar.getUserId()).isEqualTo("user-1");
    }

    /// Under-13 plumbing: the parentEmail handed to execute(...) must be forwarded
    /// verbatim as the 6th arg of register(...), so register()'s consent path runs
    /// (PENDING_CONSENT + parent email) instead of the default-deny 400. No throw.
    @Test
    void execute_under13WithParentEmail_forwardsParentEmailToRegister() {
        when(authService.emailExists("child@test.com")).thenReturn(false);
        when(authService.register(
                eq("child@test.com"), eq("pass1234"), eq("Child"),
                eq((String) null), eq(2018), eq("parent@test.com")))
                .thenReturn(new AuthResponse("user-child", "tok-child", true, false, AccountType.SOLO));
        when(avatarRepository.save(any(Avatar.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        QuickOnboardService.QuickOnboardResult result = service.execute(
                "child@test.com", "pass1234", "Child", Subject.MATHS, "primary 2",
                null, 2018, "parent@test.com");

        assertThat(result.userId()).isEqualTo("user-child");

        ArgumentCaptor<String> parentEmailCaptor = ArgumentCaptor.forClass(String.class);
        verify(authService).register(
                eq("child@test.com"), eq("pass1234"), eq("Child"),
                eq((String) null), eq(2018), parentEmailCaptor.capture());
        assertThat(parentEmailCaptor.getValue()).isEqualTo("parent@test.com");
    }

    /// THE HEADLINE INCIDENT PIN. Signup with an already-registered email must 409 —
    /// NEVER log in, NEVER issue a token, NEVER create an avatar. This was a
    /// register-OR-login upsert: an existing email was silently logged in (tokens
    /// issued for that account), so "signup" behaved as "login" — the pre-account-
    /// takeover failure. The endpoint must be indistinguishable from /auth/register.
    @Test
    void execute_existingEmail_returns409_neverLogsIn_neverIssuesToken() {
        when(authService.emailExists("kid@test.com")).thenReturn(true);

        assertThatThrownBy(() ->
                service.execute("kid@test.com", "pass1234", "Kid", Subject.SCIENCE, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Email already registered")
                .extracting(e -> ((BusinessException) e).getHttpStatus()).isEqualTo(409);

        // The invariant, asserted as absences: no login, no register, no avatar, no token path.
        verify(authService, never()).login(anyString(), anyString());
        verify(authService, never()).register(anyString(), anyString(), any(), any(), any(), any());
        verify(avatarRepository, never()).save(any(Avatar.class));
        // The OWNER (not the requester) is notified of the duplicate attempt.
        verify(duplicateSignupNotifier).notifyOwner("kid@test.com");
    }

    @Test
    void execute_nonConflictRegisterError_propagates() {
        when(authService.emailExists("kid@test.com")).thenReturn(false);
        when(authService.register("kid@test.com", "pass1234", "Kid", null, null, null))
                .thenThrow(new BusinessException("Rate limited", 429));

        assertThatThrownBy(() ->
                service.execute("kid@test.com", "pass1234", "Kid", Subject.MATHS, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Rate limited");
    }
}
