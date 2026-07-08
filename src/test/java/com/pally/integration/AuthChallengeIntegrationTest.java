package com.pally.integration;

import com.pally.infrastructure.auth.AuthChallengeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single-use challenge store (social link code + password-reset token). Pins the
 * security properties: single-use (a consumed code/token never works twice), a wrong
 * code is rejected, and the attempt cap kills brute force of a 6-digit code.
 */
class AuthChallengeIntegrationTest extends IntegrationTestBase {

    @Autowired AuthChallengeService challenges;

    @Test
    void linkCode_isSingleUse_andProviderScoped() {
        String code = challenges.createLinkCode("user-A", "google", "sub-A");

        Optional<AuthChallengeService.ConsumedLink> first =
                challenges.consumeLinkCode("user-A", "google", code);
        assertThat(first).isPresent();
        assertThat(first.get().providerSub()).isEqualTo("sub-A");

        // Single-use: the same code cannot be consumed again.
        assertThat(challenges.consumeLinkCode("user-A", "google", code)).isEmpty();
    }

    @Test
    void linkCode_wrongCode_isRejected_andWrongProviderFails() {
        String code = challenges.createLinkCode("user-B", "google", "sub-B");
        assertThat(challenges.consumeLinkCode("user-B", "google", "000000")).isEmpty();
        // right code, wrong provider → still rejected (scope enforced)
        assertThat(challenges.consumeLinkCode("user-B", "apple", code)).isEmpty();
        // right code, right provider → works (attempts didn't lock it below the cap)
        assertThat(challenges.consumeLinkCode("user-B", "google", code)).isPresent();
    }

    @Test
    void linkCode_attemptCap_locksOutBruteForce() {
        String code = challenges.createLinkCode("user-C", "google", "sub-C");
        for (int i = 0; i < 5; i++) {
            challenges.consumeLinkCode("user-C", "google", "999999"); // 5 wrong tries
        }
        // 6th attempt (even with the CORRECT code) is locked out.
        assertThat(challenges.consumeLinkCode("user-C", "google", code)).isEmpty();
    }

    @Test
    void resetToken_isSingleUse() {
        String token = challenges.createResetToken("user-D");
        assertThat(challenges.consumeResetToken(token)).contains("user-D");
        assertThat(challenges.consumeResetToken(token)).isEmpty(); // single-use
    }

    @Test
    void creatingANewChallenge_invalidatesThePriorPending() {
        String first = challenges.createLinkCode("user-E", "google", "sub-E");
        challenges.createLinkCode("user-E", "google", "sub-E"); // supersedes
        // The first code is no longer PENDING → cannot be consumed.
        assertThat(challenges.consumeLinkCode("user-E", "google", first)).isEmpty();
    }
}
