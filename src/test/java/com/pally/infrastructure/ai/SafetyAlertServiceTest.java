package com.pally.infrastructure.ai;

import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.persistence.safety.ChatSafetyFlagJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SafetyAlertServiceTest {

    @Mock ChatSafetyFlagJpaRepository flagRepo;
    @Mock EmailService emailService;

    private SafetyAlertService service;

    @BeforeEach
    void setUp() {
        service = new SafetyAlertService(flagRepo, emailService);
        ReflectionTestUtils.setField(service, "alertEmail", "admin@example.com");
    }

    @Test
    void checkAndAlert_lowSeverity_belowThreshold_noEmailSent() {
        when(flagRepo.countByChildUserIdAndCreatedAtAfter(anyString(), any(Instant.class)))
                .thenReturn(2L);

        service.checkAndAlert("user-1", "avatar-1", "msg-1", "LOW");

        verify(emailService, never()).sendHtml(any(), any(), any());
    }

    @Test
    void checkAndAlert_mediumSeverity_exactlyAtThreshold_emailSent() {
        when(flagRepo.countByChildUserIdAndCreatedAtAfter(anyString(), any(Instant.class)))
                .thenReturn(3L);

        service.checkAndAlert("user-1", "avatar-1", "msg-1", "MEDIUM");

        verify(emailService).sendHtml(eq("admin@example.com"), contains("3"), anyString());
    }

    @Test
    void checkAndAlert_lowSeverity_aboveThreshold_debounced_noEmail() {
        // count > 3 — debounce: only the 3rd flag triggers the alert, not subsequent ones
        when(flagRepo.countByChildUserIdAndCreatedAtAfter(anyString(), any(Instant.class)))
                .thenReturn(5L);

        service.checkAndAlert("user-1", "avatar-1", "msg-1", "LOW");

        verify(emailService, never()).sendHtml(any(), any(), any());
    }

    @Test
    void checkAndAlert_highSeverity_immediateAlert_withoutCountingFlags() {
        // HIGH severity → alert immediately; flagRepo.count must NOT be called
        service.checkAndAlert("user-1", "avatar-1", "msg-1", "HIGH");

        verify(emailService).sendHtml(eq("admin@example.com"), anyString(), anyString());
        verify(flagRepo, never()).countByChildUserIdAndCreatedAtAfter(any(), any());
    }

    @Test
    void checkAndAlert_repoThrows_doesNotPropagateException() {
        when(flagRepo.countByChildUserIdAndCreatedAtAfter(anyString(), any(Instant.class)))
                .thenThrow(new RuntimeException("DB down"));

        service.checkAndAlert("user-1", "avatar-1", "msg-1", "LOW"); // must not throw

        verify(emailService, never()).sendHtml(any(), any(), any());
    }
}
