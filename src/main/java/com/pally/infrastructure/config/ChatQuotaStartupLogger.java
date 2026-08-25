package com.pally.infrastructure.config;

import com.pally.domain.subscription.ChatQuotaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs the LIVE daily chat quotas once at startup:
 * <pre>[ChatQuota] free=20 pro=UNLIMITED</pre>
 *
 * <p>Same reasoning as {@code GradingWeightsStartupLogger}: the quotas are
 * {@code @ConfigurationProperties}-driven, so a Railway variable can change them
 * at runtime, and a build-time test provably cannot observe that. This makes the
 * deployed numbers readable from a running instance's boot log instead of assumed.
 *
 * <p>A log line rather than an {@code /actuator/info} field on purpose — that
 * endpoint is {@code permitAll}, and quota policy should not be published to
 * unauthenticated callers.
 *
 * <p>Read-only: a surprising value is reported, never "corrected".
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatQuotaStartupLogger {

    private final ChatQuotaProperties quotas;

    @EventListener(ApplicationReadyEvent.class)
    public void logQuotas() {
        log.info("[ChatQuota] free={} pro={}",
                describe(quotas.getFree()), describe(quotas.getPro()));
    }

    private static String describe(int limit) {
        return limit == ChatQuotaProperties.UNLIMITED ? "UNLIMITED" : String.valueOf(limit);
    }
}
