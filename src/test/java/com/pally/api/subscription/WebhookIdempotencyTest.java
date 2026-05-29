package com.pally.api.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.persistence.subscription.ProcessedStripeEventJpaEntity;
import com.pally.infrastructure.persistence.subscription.ProcessedStripeEventJpaRepository;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaRepository;
import com.pally.infrastructure.stripe.StripeService;
import com.stripe.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

/// Locks the D2 "insert-first" fix. The old check-then-act allowed two
/// concurrent re-deliveries to BOTH pass existsById before either
/// recorded → double-processed. The new code claims the event id via
/// saveAndFlush; the PK collision becomes the dedupe signal.
@ExtendWith(MockitoExtension.class)
class WebhookIdempotencyTest {

    @Mock SubscriptionJpaRepository subRepo;
    @Mock PremiumService premiumService;
    @Mock StripeService stripeService;
    @Mock ProcessedStripeEventJpaRepository processedEventRepo;

    @InjectMocks SubscriptionController controller;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(controller, "stripeSecretKey", "sk_test_xxx");
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
        when(stripeService.verifyWebhook("{}", "sig"))
                .thenReturn(event);

        var response = controller.webhook("{}", "sig");

        verify(processedEventRepo, times(1))
                .saveAndFlush(any(ProcessedStripeEventJpaEntity.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> body =
                (Map<String, Object>) response.getBody().data();
        assertThat(body.get("duplicate"))
                .as("first delivery must NOT be flagged duplicate").isNull();
    }

    /// PK collision on the claim insert → return duplicate=true; never
    /// re-run the handler. Without this, two concurrent re-deliveries
    /// would both flip a subscription, charge a credit twice, etc.
    @Test
    void duplicateDelivery_deduped() {
        Event event = eventWithId("evt_123", "customer.created");
        when(stripeService.verifyWebhook("{}", "sig"))
                .thenReturn(event);
        when(processedEventRepo.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("PK violation"));

        var response = controller.webhook("{}", "sig");

        @SuppressWarnings("unchecked")
        Map<String, Object> body =
                (Map<String, Object>) response.getBody().data();
        assertThat(body.get("duplicate")).isEqualTo(true);
    }

    /// Handler failure must release the claim so Stripe's next retry
    /// can re-attempt — without release, the next delivery would see
    /// "duplicate" and skip, losing the state mutation forever.
    @Test
    void handlerFailure_releasesClaimForRetry() {
        Event event = eventWithId("evt_123", "customer.subscription.updated");
        when(stripeService.verifyWebhook("{}", "sig")).thenReturn(event);
        // claim succeeds…
        // …but the handler will throw because the subscription payload
        // isn't deserialisable from a hand-rolled Event. That's the
        // failure path we want to test.

        controller.webhook("{}", "sig");

        verify(processedEventRepo, times(1)).deleteById("evt_123");
    }

    /// Two concurrent first-deliveries (rare, but possible if Stripe
    /// retries DURING our processing): one claims, one is deduped.
    /// Models the race the D2 fix exists to close.
    @Test
    void concurrentDeliveries_onlyOneClaims() {
        Event event = eventWithId("evt_race", "customer.created");
        when(stripeService.verifyWebhook("{}", "sig")).thenReturn(event);
        AtomicInteger claimAttempts = new AtomicInteger();
        when(processedEventRepo.saveAndFlush(any())).thenAnswer(inv -> {
            if (claimAttempts.getAndIncrement() == 0) {
                return inv.getArgument(0);
            }
            throw new DataIntegrityViolationException("PK violation");
        });

        var first = controller.webhook("{}", "sig");
        var second = controller.webhook("{}", "sig");

        @SuppressWarnings("unchecked")
        Map<String, Object> b1 = (Map<String, Object>) first.getBody().data();
        @SuppressWarnings("unchecked")
        Map<String, Object> b2 = (Map<String, Object>) second.getBody().data();
        assertThat(b1.get("duplicate")).isNull();
        assertThat(b2.get("duplicate")).isEqualTo(true);
        // The handler should NOT have been invoked for the duplicate.
        verify(processedEventRepo, never()).deleteById(any());
    }
}
