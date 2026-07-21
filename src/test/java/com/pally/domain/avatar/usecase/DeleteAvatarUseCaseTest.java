package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.chat.port.ChatSessionCachePort;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting an avatar must cancel its cache-keepalive ticker BEFORE the row is
 * removed — otherwise the scheduled ping keeps polling the dead avatar every
 * ~4 min (the keepalive leak in the delete-mid-compile trace).
 */
@ExtendWith(MockitoExtension.class)
class DeleteAvatarUseCaseTest {

    @Mock AvatarRepository avatarRepository;
    @Mock ChatSessionCachePort chatSessionCachePort;
    @InjectMocks DeleteAvatarUseCase useCase;

    private Avatar personalAvatar(String userId) {
        Avatar a = mock(Avatar.class);
        when(a.getUserId()).thenReturn(userId);
        when(a.isCentreClass()).thenReturn(false);
        return a;
    }

    @Test
    void delete_stopsKeepaliveBeforeDeletingTheRow() {
        Avatar avatar = personalAvatar("u");
        when(avatarRepository.findById("av")).thenReturn(Optional.of(avatar));

        useCase.execute("av", "u");

        // Ordering matters: cancel the ticker while the avatar still exists so no
        // ping can fire against a deleted row.
        var order = inOrder(chatSessionCachePort, avatarRepository);
        order.verify(chatSessionCachePort).stopKeepalive("av");
        order.verify(avatarRepository).deleteById("av");
    }

    @Test
    void delete_stillDeletes_whenStopKeepaliveThrows() {
        Avatar avatar = personalAvatar("u");
        when(avatarRepository.findById("av")).thenReturn(Optional.of(avatar));
        org.mockito.Mockito.doThrow(new RuntimeException("scheduler down"))
                .when(chatSessionCachePort).stopKeepalive("av");

        useCase.execute("av", "u");

        // A keepalive-cancel failure is best-effort and must never block the delete.
        verify(avatarRepository).deleteById("av");
    }

    @Test
    void classAvatar_isRejected_andNeverTouchesKeepaliveOrDelete() {
        Avatar clazz = mock(Avatar.class);
        when(clazz.getUserId()).thenReturn("u");
        when(clazz.isCentreClass()).thenReturn(true);
        when(avatarRepository.findById("cls")).thenReturn(Optional.of(clazz));

        assertThatThrownBy(() -> useCase.execute("cls", "u"))
                .isInstanceOf(BusinessException.class);

        verify(chatSessionCachePort, never()).stopKeepalive("cls");
        verify(avatarRepository, never()).deleteById("cls");
    }

    @Test
    void missingAvatar_throwsNotFound_andNeverTouchesKeepalive() {
        when(avatarRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute("ghost", "u"))
                .isInstanceOf(AvatarNotFoundException.class);

        verify(chatSessionCachePort, never()).stopKeepalive("ghost");
    }
}
