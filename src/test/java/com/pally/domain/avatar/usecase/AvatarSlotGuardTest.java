package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Fix 2 — AvatarSlotGuard: locked avatars must be blocked from brain-mutating operations.
 *
 * <p>Invariants:
 * <ul>
 *   <li>is_active=false  → 409 BusinessException</li>
 *   <li>is_active=true   → no throw</li>
 *   <li>avatar not found → AvatarNotFoundException</li>
 *   <li>DELETE paths must NOT call the guard (tested separately in each use-case test)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AvatarSlotGuardTest {

    @Mock AvatarRepository avatarRepository;

    private AvatarSlotGuard guard;

    private static final String USER_ID = "user-guard-test";
    private static final String AVATAR_ID = "avatar-guard-test";
    private static final String OTHER_USER = "other-user";

    @BeforeEach
    void setUp() {
        guard = new AvatarSlotGuard(avatarRepository);
    }

    @Test
    void requireActive_lockedAvatar_throws409() {
        Avatar locked = buildAvatar(false);
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> guard.requireActive(AVATAR_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("locked")
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(409);
                });
    }

    @Test
    void requireActive_activeAvatar_doesNotThrow() {
        Avatar active = buildAvatar(true);
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(active));

        assertThatCode(() -> guard.requireActive(AVATAR_ID, USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void requireActive_avatarNotFound_throwsAvatarNotFoundException() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireActive(AVATAR_ID, USER_ID))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void requireActive_avatarBelongsToOtherUser_throwsAvatarNotFoundException() {
        // Avatar exists but belongs to a different user — guard must not reveal it exists
        Avatar otherUser = buildAvatarFor(OTHER_USER, true);
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(otherUser));

        assertThatThrownBy(() -> guard.requireActive(AVATAR_ID, USER_ID))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Avatar buildAvatar(boolean isActive) {
        return buildAvatarFor(USER_ID, isActive);
    }

    private Avatar buildAvatarFor(String userId, boolean isActive) {
        return Avatar.reconstitute(
                AVATAR_ID, userId, "Nomi", Subject.SCIENCE, CharacterType.MOCHI,
                0, Instant.now(), null, null,
                Avatar.PedagogyMode.SOCRATIC, com.pally.domain.avatar.TeachingMode.TEACHING,
                null, Avatar.BrainState.READY, isActive);
    }

    // Import assertThat directly (not via static import collision with Mockito)
    private static <T> org.assertj.core.api.AbstractObjectAssert<?, T> assertThat(T actual) {
        return org.assertj.core.api.Assertions.assertThat(actual);
    }
}
