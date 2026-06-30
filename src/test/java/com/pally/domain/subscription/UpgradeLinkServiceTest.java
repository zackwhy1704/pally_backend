package com.pally.domain.subscription;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.push.FcmService;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpgradeLinkServiceTest {

    private static final String USER_ID = "user-123";
    private static final String EMAIL = "kid@test.com";
    private static final String BASE = "https://apalchi.com";
    private static final String EXPECTED_URL = BASE + "/account/billing";

    @Mock UserRepository userRepository;
    @Mock EmailService emailService;
    @Mock FcmService fcmService;
    @Mock SlidingWindowRateLimiter rateLimiter;

    UpgradeLinkService service;

    @BeforeEach
    void setUp() {
        service = new UpgradeLinkService(userRepository, emailService, fcmService, rateLimiter);
        ReflectionTestUtils.setField(service, "webBaseUrl", BASE);
    }

    private void allowRateLimit() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(SlidingWindowRateLimiter.Result.ok());
    }

    private void userExists() {
        User u = new User();
        u.setId(USER_ID);
        u.setEmail(EMAIL);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
    }

    @Test
    void nullChannels_defaultsToBothEmailAndPush() {
        allowRateLimit();
        userExists();
        when(emailService.isConfigured()).thenReturn(true);
        when(fcmService.isConfigured()).thenReturn(true);

        Map<String, Object> out = service.sendUpgradeLink(USER_ID, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) out.get("sent");
        assertThat(sent.get("email")).isEqualTo(true);
        assertThat(sent.get("push")).isEqualTo(true);
        verify(emailService).sendHtml(eq(EMAIL), anyString(), anyString());
        verify(fcmService).sendToUser(eq(USER_ID), anyString(), anyString(), any());
    }

    @Test
    void emailOnlyChannel_dispatchesEmailNotPush() {
        allowRateLimit();
        userExists();
        when(emailService.isConfigured()).thenReturn(true);

        Map<String, Object> out = service.sendUpgradeLink(USER_ID, List.of("email"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) out.get("sent");
        assertThat(sent.get("email")).isEqualTo(true);
        assertThat(sent.get("push")).isEqualTo(false);
        verify(emailService).sendHtml(eq(EMAIL), anyString(), anyString());
        verify(fcmService, never()).sendToUser(anyString(), anyString(), anyString(), any());
    }

    @Test
    void pushOnlyChannel_dispatchesPushNotEmail() {
        allowRateLimit();
        userExists();
        when(fcmService.isConfigured()).thenReturn(true);

        Map<String, Object> out = service.sendUpgradeLink(USER_ID, List.of("push"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) out.get("sent");
        assertThat(sent.get("push")).isEqualTo(true);
        assertThat(sent.get("email")).isEqualTo(false);
        verify(fcmService).sendToUser(eq(USER_ID), anyString(), anyString(), any());
        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void responseShape_alwaysHasSentWithBothChannelBooleans() {
        allowRateLimit();
        userExists();
        // Neither channel configured → both report false, but keys must still be present.
        Map<String, Object> out = service.sendUpgradeLink(USER_ID, null);

        assertThat(out).containsOnlyKeys("sent");
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) out.get("sent");
        assertThat(sent).containsOnlyKeys("email", "push");
        assertThat(sent.get("email")).isEqualTo(false);
        assertThat(sent.get("push")).isEqualTo(false);
    }

    @Test
    void pushPayload_carriesBillingUrlForDeepLink() {
        allowRateLimit();
        userExists();
        when(fcmService.isConfigured()).thenReturn(true);

        service.sendUpgradeLink(USER_ID, List.of("push"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(USER_ID), anyString(), anyString(), data.capture());
        assertThat(data.getValue()).containsEntry("billingUrl", EXPECTED_URL);
    }

    @Test
    void unknownChannelNames_fallBackToBothRatherThanSendingNothing() {
        allowRateLimit();
        userExists();
        when(emailService.isConfigured()).thenReturn(true);
        when(fcmService.isConfigured()).thenReturn(true);

        Map<String, Object> out = service.sendUpgradeLink(USER_ID, List.of("sms", "carrierpigeon"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) out.get("sent");
        assertThat(sent.get("email")).isEqualTo(true);
        assertThat(sent.get("push")).isEqualTo(true);
    }

    @Test
    void userWithNoEmail_reportsEmailFalseEvenWhenProviderConfigured() {
        allowRateLimit();
        User u = new User();
        u.setId(USER_ID);
        u.setEmail(null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        Map<String, Object> out = service.sendUpgradeLink(USER_ID, List.of("email"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) out.get("sent");
        assertThat(sent.get("email")).isEqualTo(false);
        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void rateLimitExceeded_throws429AndSendsNothing() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(SlidingWindowRateLimiter.Result.deny(42));

        assertThatThrownBy(() -> service.sendUpgradeLink(USER_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many requests")
                .extracting(e -> ((BusinessException) e).getHttpStatus())
                .isEqualTo(429);

        verifyNoInteractions(emailService, fcmService);
    }

    @Test
    void unknownUser_throws404() {
        allowRateLimit();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendUpgradeLink(USER_ID, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getHttpStatus())
                .isEqualTo(404);
    }

    @Test
    void blankUserId_throws401AndNeverTouchesRateLimiter() {
        assertThatThrownBy(() -> service.sendUpgradeLink("  ", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getHttpStatus())
                .isEqualTo(401);
        verifyNoInteractions(rateLimiter, userRepository, emailService, fcmService);
    }
}
