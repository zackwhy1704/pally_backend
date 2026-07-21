package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.chat.port.ChatSessionCachePort;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Students cannot delete or edit a CENTRE_CLASS avatar — these are centre-owned.
 * Both the delete and the settings-update paths must reject with 403 BEFORE any
 * persistence happens, while PERSONAL avatars stay fully editable/deletable.
 */
@ExtendWith(MockitoExtension.class)
class CentreClassAvatarGuardTest {

    @Mock AvatarRepository avatarRepository;
    @Mock ChatSessionCachePort chatSessionCachePort;

    private static final String USER_ID = "student-1";
    private static final String AVATAR_ID = "avatar-1";

    private Avatar personalAvatar() {
        return Avatar.reconstitute(AVATAR_ID, USER_ID, "My Mochi", Subject.SCIENCE,
                CharacterType.MOCHI, 0, java.time.Instant.now());
    }

    private Avatar classAvatar() {
        Avatar a = Avatar.reconstitute(AVATAR_ID, USER_ID, "P4 Math", Subject.MATHS,
                CharacterType.MOCHI, 0, java.time.Instant.now());
        a.markCentreClassAvatar();
        a.setClassId("class-1");
        return a;
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    void delete_classAvatar_isForbidden_andNeverDeletes() {
        DeleteAvatarUseCase useCase = new DeleteAvatarUseCase(avatarRepository, chatSessionCachePort);
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(classAvatar()));

        assertThatThrownBy(() -> useCase.execute(AVATAR_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(403));
        verify(avatarRepository, never()).deleteById(AVATAR_ID);
    }

    @Test
    void delete_personalAvatar_succeeds() {
        DeleteAvatarUseCase useCase = new DeleteAvatarUseCase(avatarRepository, chatSessionCachePort);
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(personalAvatar()));

        useCase.execute(AVATAR_ID, USER_ID);

        verify(avatarRepository).deleteById(AVATAR_ID);
    }

    // ── update / rename ────────────────────────────────────────────────────────

    @Test
    void updateTestDate_classAvatar_isForbidden_andNeverSaves() {
        GetAvatarUseCase getUseCase = new GetAvatarUseCase(avatarRepository);
        UpdateAvatarSettingsUseCase useCase = new UpdateAvatarSettingsUseCase(avatarRepository, getUseCase);
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(classAvatar()));

        assertThatThrownBy(() -> useCase.updateTestDate(AVATAR_ID, USER_ID, LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(403));
        verify(avatarRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateGradeCurriculum_classAvatar_isForbidden() {
        GetAvatarUseCase getUseCase = new GetAvatarUseCase(avatarRepository);
        UpdateAvatarSettingsUseCase useCase = new UpdateAvatarSettingsUseCase(avatarRepository, getUseCase);
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(classAvatar()));

        assertThatThrownBy(() -> useCase.updateGradeCurriculum(AVATAR_ID, USER_ID, "P4", "MOE"))
                .isInstanceOf(BusinessException.class);
        verify(avatarRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateTestDate_personalAvatar_succeeds() {
        GetAvatarUseCase getUseCase = new GetAvatarUseCase(avatarRepository);
        UpdateAvatarSettingsUseCase useCase = new UpdateAvatarSettingsUseCase(avatarRepository, getUseCase);
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(personalAvatar()));
        when(avatarRepository.save(org.mockito.ArgumentMatchers.any(Avatar.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Avatar result = useCase.updateTestDate(AVATAR_ID, USER_ID, LocalDate.of(2030, 1, 1));

        assertThat(result.getTestDate()).isEqualTo(LocalDate.of(2030, 1, 1));
        verify(avatarRepository).save(org.mockito.ArgumentMatchers.any(Avatar.class));
    }
}
