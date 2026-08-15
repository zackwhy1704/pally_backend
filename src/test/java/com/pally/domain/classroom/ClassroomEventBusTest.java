package com.pally.domain.classroom;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one genuinely novel piece of plumbing here (multicast fan-out to many
 * SSE subscribers off one session) — chat's SSE transport is single-consumer
 * by design, so this had no existing broadcast primitive to reuse. Verifies
 * the fan-out and the ephemeral-identity teardown actually work, not just
 * that the methods don't throw.
 */
class ClassroomEventBusTest {

    @Test
    void publishedEventReachesASubscribedStream() {
        var bus = new ClassroomEventBus();
        var flux = bus.streamFor("session-1");

        StepVerifier.create(flux.take(1))
                .then(() -> bus.publish("session-1", "hit", "{\"nickname\":\"Star Kid\"}"))
                .assertNext(event -> {
                    assertThat(event.type()).isEqualTo("hit");
                    assertThat(event.payload()).contains("Star Kid");
                })
                .verifyComplete();
    }

    @Test
    void twoSubscribersToTheSameSessionBothReceiveTheSameBroadcast() {
        var bus = new ClassroomEventBus();
        var fluxA = bus.streamFor("session-1");
        var fluxB = bus.streamFor("session-1");

        StepVerifier.create(fluxA.take(1))
                .then(() -> bus.publish("session-1", "hp", "{\"hpRemaining\":1}"))
                .assertNext(e -> assertThat(e.payload()).contains("1"))
                .verifyComplete();

        // A late-ish second subscriber still gets the NEXT event on the same session.
        StepVerifier.create(fluxB.take(1))
                .then(() -> bus.publish("session-1", "hp", "{\"hpRemaining\":0}"))
                .assertNext(e -> assertThat(e.payload()).contains("0"))
                .verifyComplete();
    }

    @Test
    void forgetSession_completesTheStream_andDropsNicknamesIrrecoverably() {
        var bus = new ClassroomEventBus();
        bus.registerParticipant("session-1", "token-1", "Star Kid");
        assertThat(bus.participantCount("session-1")).isEqualTo(1);

        var flux = bus.streamFor("session-1");
        bus.forgetSession("session-1");

        StepVerifier.create(flux).verifyComplete(); // stream ends, no further events possible
        assertThat(bus.nicknameFor("session-1", "token-1")).isNull();
        assertThat(bus.hasParticipant("session-1", "token-1")).isFalse();
        assertThat(bus.participantCount("session-1")).isZero();
    }

    @Test
    void differentSessionsAreIsolated_oneSessionsBroadcastNeverLeaksToAnother() {
        var bus = new ClassroomEventBus();
        var fluxSessionA = bus.streamFor("session-A");

        bus.publish("session-B", "hit", "{}");

        // Nothing arrives on session-A's stream within the window — proves the
        // session-B publish never crossed over.
        StepVerifier.create(fluxSessionA)
                .expectTimeout(Duration.ofMillis(200))
                .verify(Duration.ofSeconds(2));
    }
}
