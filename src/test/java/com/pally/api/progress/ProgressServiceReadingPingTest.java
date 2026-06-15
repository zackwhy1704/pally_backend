package com.pally.api.progress;

import com.pally.api.progress.dto.ReadingPingRequest;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.progress.ProgressService;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.progress.ActivityLogService;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/// Unit tests for the reading-time ping. The ping lets the mobile client report
/// wiki/lesson reading time so the weekly minutes chart is honest. Duration is
/// clamped server-side; ownership is enforced. (Moved from the controller test
/// to {@link ProgressService} in the controller→service refactor.)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProgressServiceReadingPingTest {

    @Mock AvatarRepository avatarRepository;
    @Mock ActivityLogService activityLogService;

    @InjectMocks ProgressService service;

    private static final String USER = "user-1";
    private static final String AVATAR = "avatar-1";

    private Avatar ownedAvatar() {
        return Avatar.reconstitute(AVATAR, USER, "MathBot", Subject.MATHS,
                CharacterType.ZAP, 0, Instant.now());
    }

    @Test
    void reading_logsClampedDuration() {
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(ownedAvatar()));

        Map<String, Object> result = service.logReading(USER,
                new ReadingPingRequest(AVATAR, 99999));

        assertThat(result.get("durationSeconds")).isEqualTo(3600);
        // Logs a READING activity with the clamped duration and 0 XP.
        verify(activityLogService).log(eq(USER), eq(AVATAR),
                eq(ActivityLogService.TYPE_READING), eq(3600), eq(0));
    }

    @Test
    void reading_normalDuration_loggedAsIs() {
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(ownedAvatar()));

        service.logReading(USER, new ReadingPingRequest(AVATAR, 240));

        verify(activityLogService).log(eq(USER), eq(AVATAR),
                eq(ActivityLogService.TYPE_READING), eq(240), eq(0));
    }

    @Test
    void reading_nullDuration_loggedAsZero() {
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(ownedAvatar()));

        service.logReading(USER, new ReadingPingRequest(AVATAR, null));

        verify(activityLogService).log(eq(USER), eq(AVATAR),
                eq(ActivityLogService.TYPE_READING), eq(0), eq(0));
    }

    @Test
    void reading_missingAvatarId_throws400_noLog() {
        assertThatThrownBy(() -> service.logReading(USER,
                new ReadingPingRequest(null, 120)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("avatarId is required");
        verifyNoInteractions(activityLogService);
    }

    @Test
    void reading_avatarNotOwned_throws404_noLog() {
        // Avatar belongs to someone else → filter drops it → 404.
        Avatar foreign = Avatar.reconstitute(AVATAR, "someone-else", "X",
                Subject.MATHS, CharacterType.ZAP, 0, Instant.now());
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.logReading(USER,
                new ReadingPingRequest(AVATAR, 120)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Avatar not found");
        verifyNoInteractions(activityLogService);
    }
}
