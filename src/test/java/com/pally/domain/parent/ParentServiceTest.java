package com.pally.domain.parent;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.WeeklyReportService;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParentServiceTest {

    @Mock UserRepository userRepo;
    @Mock ActivityLogRepository activityLogRepo;
    @Mock ActivityLogService activityLogService;
    @Mock QuizWeakAreaRepository quizWeakAreaRepo;
    @Mock AvatarRepository avatarRepository;
    @Mock WikiRepository wikiRepository;
    @Mock WeeklyReportService weeklyReportService;

    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    ParentService service;

    private static final String USER_ID = "user-1";

    @BeforeEach
    void setUp() {
        service = new ParentService(
                userRepo, activityLogRepo, activityLogService, quizWeakAreaRepo,
                avatarRepository, wikiRepository, encoder, weeklyReportService);
    }

    // ── PIN status ─────────────────────────────────────────────────────────

    @Test
    void hasPinSet_noPinHash_returnsFalse() {
        when(userRepo.getParentPinHash(USER_ID)).thenReturn(Optional.empty());
        assertThat(service.hasPinSet(USER_ID)).isFalse();
    }

    @Test
    void hasPinSet_pinHashPresent_returnsTrue() {
        when(userRepo.getParentPinHash(USER_ID)).thenReturn(Optional.of("$2a$hash"));
        assertThat(service.hasPinSet(USER_ID)).isTrue();
    }

    // ── Set PIN ────────────────────────────────────────────────────────────

    @Test
    void setPin_encodesAndSaves() {
        service.setPin(USER_ID, "1234");
        // Verify repository was called with a bcrypt hash (not plaintext).
        verify(userRepo).setParentPinHash(eq(USER_ID), anyString());
    }

    // ── Verify PIN ─────────────────────────────────────────────────────────

    @Test
    void verifyPin_noHashSet_treatsAsFirstTimeSetup() {
        when(userRepo.getParentPinHash(USER_ID)).thenReturn(Optional.empty());

        ParentService.VerifyPinResult result = service.verifyPin(USER_ID, "1234");

        assertThat(result.verified()).isTrue();
        assertThat(result.firstTimeSetup()).isTrue();
        verify(userRepo).setParentPinHash(eq(USER_ID), anyString());
    }

    @Test
    void verifyPin_correctPin_returnsVerifiedTrue() {
        String hash = encoder.encode("5678");
        when(userRepo.getParentPinHash(USER_ID)).thenReturn(Optional.of(hash));

        ParentService.VerifyPinResult result = service.verifyPin(USER_ID, "5678");

        assertThat(result.verified()).isTrue();
        assertThat(result.firstTimeSetup()).isFalse();
        assertThat(result.lockedOut()).isFalse();
    }

    @Test
    void verifyPin_wrongPin_returnsVerifiedFalse_andDecrementsAttempts() {
        String hash = encoder.encode("9999");
        when(userRepo.getParentPinHash(USER_ID)).thenReturn(Optional.of(hash));

        ParentService.VerifyPinResult result = service.verifyPin(USER_ID, "1111");

        assertThat(result.verified()).isFalse();
        assertThat(result.lockedOut()).isFalse();
        assertThat(result.attemptsRemaining()).isEqualTo(4); // 5 - 1
    }

    @Test
    void verifyPin_fiveConsecutiveFailures_causesLockout() {
        String hash = encoder.encode("9999");
        when(userRepo.getParentPinHash(USER_ID)).thenReturn(Optional.of(hash));

        // 4 failed attempts
        for (int i = 0; i < 4; i++) {
            service.verifyPin(USER_ID, "wrong");
        }
        // 5th attempt triggers lockout
        ParentService.VerifyPinResult result = service.verifyPin(USER_ID, "wrong");

        assertThat(result.lockedOut()).isTrue();
        assertThat(result.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void verifyPin_whileLocked_returnsLockedOut() {
        String hash = encoder.encode("9999");
        when(userRepo.getParentPinHash(USER_ID)).thenReturn(Optional.of(hash));

        // exhaust attempts to get locked
        for (int i = 0; i < 5; i++) {
            service.verifyPin(USER_ID, "wrong");
        }
        // subsequent attempt while locked
        ParentService.VerifyPinResult result = service.verifyPin(USER_ID, "9999");

        assertThat(result.lockedOut()).isTrue();
        assertThat(result.verified()).isFalse();
    }

    // ── Reset PIN ──────────────────────────────────────────────────────────

    @Test
    void resetPin_correctPassword_updatesHash() {
        String passwordHash = encoder.encode("myPassword");
        when(userRepo.getPasswordHash(USER_ID)).thenReturn(Optional.of(passwordHash));

        service.resetPin(USER_ID, "myPassword", "4321");

        verify(userRepo).setParentPinHash(eq(USER_ID), anyString());
    }

    @Test
    void resetPin_wrongPassword_throws401() {
        String passwordHash = encoder.encode("realPassword");
        when(userRepo.getPasswordHash(USER_ID)).thenReturn(Optional.of(passwordHash));

        assertThatThrownBy(() -> service.resetPin(USER_ID, "wrongPassword", "4321"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Incorrect password");
        verify(userRepo, never()).setParentPinHash(anyString(), anyString());
    }

    @Test
    void resetPin_noPasswordSet_throws401() {
        when(userRepo.getPasswordHash(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPin(USER_ID, "anything", "4321"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Incorrect password");
    }

    // ── Dashboard ──────────────────────────────────────────────────────────

    @Test
    void getDashboard_unknownUser_throws404() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDashboard(USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void getDashboard_knownUser_aggregatesData() {
        User u = user(USER_ID);
        u.setLevel(5);
        u.setStreakDays(10);
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(u));
        when(activityLogRepo.countSince(anyString(), any(Instant.class))).thenReturn(3);
        when(activityLogRepo.sumXpSince(anyString(), any(Instant.class))).thenReturn(120);
        when(activityLogRepo.sumMinutesBetween(anyString(), any(), any())).thenReturn(45);
        when(activityLogService.minutesPerDayLast7(USER_ID)).thenReturn(List.of(0,5,10,0,15,10,5));
        when(weeklyReportService.computeSubjectMastery(USER_ID)).thenReturn(List.of());
        when(quizWeakAreaRepo.findWeakestTopics(USER_ID, 5)).thenReturn(List.of());
        when(avatarRepository.findByUserId(USER_ID)).thenReturn(List.of());

        var dashboard = service.getDashboard(USER_ID);

        assertThat(dashboard.sessionsThisWeek()).isEqualTo(3);
        assertThat(dashboard.xpThisWeek()).isEqualTo(120);
        assertThat(dashboard.minutesThisWeek()).isEqualTo(45);
        assertThat(dashboard.level()).isEqualTo(5);
        assertThat(dashboard.streakDays()).isEqualTo(10);
    }

    // ── Screen time ────────────────────────────────────────────────────────

    @Test
    void setScreenTime_callsRepositoryWithParsedValues() {
        User u = user(USER_ID);
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(u));

        service.setScreenTime(USER_ID, true, 90);

        verify(userRepo).setScreenTime(USER_ID, true, 90);
    }

    @Test
    void setScreenTime_nullEnabled_keepsExistingEnabled() {
        User u = user(USER_ID);
        u.setScreenTimeEnabled(true);
        u.setScreenTimeMinutes(60);
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(u));

        service.setScreenTime(USER_ID, null, 120);

        verify(userRepo).setScreenTime(USER_ID, true, 120);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private User user(String id) {
        User u = new User();
        u.setId(id);
        u.setScreenTimeEnabled(false);
        u.setScreenTimeMinutes(60);
        return u;
    }
}
