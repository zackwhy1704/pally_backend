package com.pally.domain.knowledge;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A brainState transition must NO-OP when the avatar no longer exists — a
 * markReady() firing on a deleted avatar produced the phantom
 * {@code [Brain] … → READY} in the delete-mid-compile trace. The guard is
 * existence-based: no findById hit ⇒ no save, no state write, no log line.
 */
@ExtendWith(MockitoExtension.class)
class BrainStateServiceTest {

    @Mock AvatarRepository avatarRepository;
    @InjectMocks BrainStateService service;

    @Test
    void markReady_onDeletedAvatar_noOps_neverSaves() {
        when(avatarRepository.findById("gone")).thenReturn(Optional.empty());

        service.markReady("gone");

        // No save ⇒ no state written ⇒ the "[Brain] → READY" line never fires for
        // a deleted avatar.
        verify(avatarRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void markCompiling_onDeletedAvatar_noOps_neverSaves() {
        when(avatarRepository.findById("gone")).thenReturn(Optional.empty());

        service.markCompiling("gone");

        verify(avatarRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void markPending_onDeletedAvatar_noOps_neverSaves() {
        when(avatarRepository.findById("gone")).thenReturn(Optional.empty());

        service.markPending("gone");

        verify(avatarRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void markReady_onLiveAvatar_setsStateAndSaves() {
        Avatar live = mock(Avatar.class);
        when(avatarRepository.findById("live")).thenReturn(Optional.of(live));

        service.markReady("live");

        verify(live).setBrainState(Avatar.BrainState.READY);
        verify(avatarRepository).save(live);
    }
}
