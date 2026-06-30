package com.pally.domain.onboard;

import com.pally.domain.account.AccountType;

import com.pally.domain.auth.dto.AuthResponse;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.infrastructure.auth.AuthService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuickOnboardServiceTest {

    @Mock private AuthService authService;
    @Mock private AvatarRepository avatarRepository;

    private QuickOnboardService service;

    @BeforeEach
    void setUp() {
        service = new QuickOnboardService(authService, avatarRepository);
    }

    @Test
    void execute_newUser_registersAndCreatesAvatar() {
        when(authService.emailExists("kid@test.com")).thenReturn(false);
        when(authService.register("kid@test.com", "pass1234", "Kid", null, null))
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

    @Test
    void execute_existingUser_loginAndCreatesAvatar() {
        when(authService.emailExists("kid@test.com")).thenReturn(true);
        when(authService.login("kid@test.com", "pass1234"))
                .thenReturn(new AuthResponse("user-existing", "tok-2", false, true, AccountType.SOLO));
        when(avatarRepository.save(any(Avatar.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        QuickOnboardService.QuickOnboardResult result =
                service.execute("kid@test.com", "pass1234", null, Subject.SCIENCE, null);

        assertThat(result.token()).isEqualTo("tok-2");
        assertThat(result.userId()).isEqualTo("user-existing");

        verify(authService).login("kid@test.com", "pass1234");
    }

    /// Regression: existing-user onboard must NOT call register(). register() is
    /// @Transactional; a 409 thrown across its boundary marks the shared
    /// transaction rollback-only, which made the commit fail with
    /// UnexpectedRollbackException (a 500) on every existing-user re-onboard.
    @Test
    void execute_existingUser_doesNotCallRegister() {
        when(authService.emailExists("kid@test.com")).thenReturn(true);
        when(authService.login("kid@test.com", "pass1234"))
                .thenReturn(new AuthResponse("user-existing", "tok-2", false, true, AccountType.SOLO));
        when(avatarRepository.save(any(Avatar.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.execute("kid@test.com", "pass1234", null, Subject.SCIENCE, null);

        verify(authService, never())
                .register(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void execute_wrongPassword_throwsBusinessException() {
        when(authService.emailExists("kid@test.com")).thenReturn(true);
        when(authService.login("kid@test.com", "wrong"))
                .thenThrow(new BusinessException("Invalid email or password", 401));

        assertThatThrownBy(() ->
                service.execute("kid@test.com", "wrong", "Kid", Subject.ENGLISH, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void execute_nonConflictRegisterError_propagates() {
        when(authService.emailExists("kid@test.com")).thenReturn(false);
        when(authService.register("kid@test.com", "pass1234", "Kid", null, null))
                .thenThrow(new BusinessException("Rate limited", 429));

        assertThatThrownBy(() ->
                service.execute("kid@test.com", "pass1234", "Kid", Subject.MATHS, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Rate limited");
    }
}
