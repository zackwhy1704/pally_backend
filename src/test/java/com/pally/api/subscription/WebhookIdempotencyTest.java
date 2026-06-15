package com.pally.api.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.subscription.ProcessedStripeEventRepository;
import com.pally.domain.subscription.SubscriptionManagementService;
import com.pally.domain.subscription.SubscriptionRepository;
import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.stripe.StripeService;
import com.stripe.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the D2 "insert-first" idempotency fix for Stripe webhooks.
 *
 * <p>The old check-then-act pattern allowed two concurrent re-deliveries to BOTH
 * pass existsById before either recorded → double-processed. The new code claims
 * the event id via {@link ProcessedStripeEventRepository#claimEvent}; a PK
 * collision (signalled as {@code false} return) becomes the dedupe signal.
 *
 * <p>Tests drive {@link SubscriptionManagementService} directly, which owns
 * all webhook logic. The controller is now a pure routing shell.
 */
@ExtendWith(MockitoExtension.class)
class WebhookIdempotencyTest {

    @Mock SubscriptionRepository      subscriptionRepo;
    @Mock ProcessedStripeEventRepository processedEventRepo;
    @Mock PremiumService              premiumService;
    @Mock StripeService               stripeService;

    private SubscriptionManagementService service;

    @BeforeEach
    void setup() {
        service = new SubscriptionManagementService(
                subscriptionRepo, processedEventRepo, premiumService,
                stripeService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "stripeSecretKey", "sk_test_xxx");
        ReflectionTestUtils.setField(service, "mockWebhookSecret", "");
    }

    private Event eventWithId(String id, String type) {
        Event event = new Event();
        event.setId(id);
        event.setType(type);
        return event;
    }

    @Test
    void firstDelivery_claimsAndProcesses() {
        Event event = eventWithId("evt_123", "customer.created");
        when(stripeService.verifyWebhook("{}", "sig")).thenReturn(event);
        when(processedEventRepo.claimEvent(any(), any(), any())).thenReturn(true);

        Map<String, Object> body = service.handleLiveWebhook("{}", "sig");

        verify(processedEventRepo, times(1)).claimEvent(
                org.mockito.ArgumentMatchers.eq("evt_123"),
                org.mockito.ArgumentMatchers.eq("customer.created"),
                any());
        assertThat(body.get("duplicate"))
                .as("first delivery must NOT be flagged duplicate").isNull();
    }

    /**
     * PK collision on the claim insert → return duplicate=true; never
     * re-run the handler. Without this, two concurrent re-deliveries
     * would both flip a subscription, charge a credit twice, etc.
     */
    @Test
    void duplicateDelivery_deduped() {
        Event event = eventWithId("evt_123", "customer.created");
        when(stripeService.verifyWebhook("{}", "sig")).thenReturn(event);
        when(processedEventRepo.claimEvent(any(), any(), any())).thenReturn(false);

        Map<String, Object> body = service.handleLiveWebhook("{}", "sig");

        assertThat(body.get("duplicate")).isEqualTo(true);
    }

    /**
     * Handler failure must release the claim so Stripe's next retry
     * can re-attempt — without release, the next delivery would see
     * "duplicate" and skip, losing the state mutation forever.
     */
    @Test
    void handlerFailure_releasesClaimForRetry() {
        Event event = eventWithId("evt_123", "customer.subscription.updated");
        when(stripeService.verifyWebhook("{}", "sig")).thenReturn(event);
        when(processedEventRepo.claimEvent(any(), any(), any())).thenReturn(true);
        // The handler will throw because the subscription payload isn't
        // deserialisable from a hand-rolled Event — that is the failure path.

        service.handleLiveWebhook("{}", "sig");

        verify(processedEventRepo, times(1)).releaseEvent("evt_123");
    }

    /**
     * Two concurrent first-deliveries (rare, but possible if Stripe retries
     * DURING our processing): one claims, one is deduped.
     * Models the race the D2 fix exists to close.
     */
    @Test
    void concurrentDeliveries_onlyOneClaims() {
        Event event = eventWithId("evt_race", "customer.created");
        when(stripeService.verifyWebhook("{}", "sig")).thenReturn(event);
        AtomicInteger claimAttempts = new AtomicInteger();
        when(processedEventRepo.claimEvent(any(), any(), any())).thenAnswer(inv -> {
            // First call claims; second simulates a concurrent insert losing the race
            return claimAttempts.getAndIncrement() == 0;
        });

        Map<String, Object> b1 = service.handleLiveWebhook("{}", "sig");
        Map<String, Object> b2 = service.handleLiveWebhook("{}", "sig");

        assertThat(b1.get("duplicate")).isNull();
        assertThat(b2.get("duplicate")).isEqualTo(true);
        // The handler failure path should NOT be triggered for the duplicate.
        verify(processedEventRepo, never()).releaseEvent(any());
    }
}
