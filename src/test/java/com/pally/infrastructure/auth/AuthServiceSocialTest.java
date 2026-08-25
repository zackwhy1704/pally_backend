package com.pally.infrastructure.auth;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.LinkRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Social sign-in security + sub-keying. Never silently auto-link to a PASSWORD account
 * (the takeover vector); an unverified email is attacker-controllable so it never matches;
 * sub-keying is the stable identity, with a one-time lazy backfill of legacy email rows.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceSocialTest {

    @Mock UserJpaRepository userRepo;
    @Mock JwtService jwtService;
    @Mock com.pally.domain.shop.CharacterShopService characterShopService;
    @Mock com.pally.domain.progress.BadgeService badgeService;
    @Mock com.pally.domain.progress.StreakService streakService;
    @Mock com.pally.domain.consent.ConsentService consentService;
    @InjectMocks AuthService authService;

    @BeforeEach
    void noSubMatchByDefault() {
        lenient().when(userRepo.findByProviderAndProviderSub(anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(jwtService.generateToken(anyString(), any(), anyInt())).thenReturn("tok");
        lenient().when(streakService.recordActiveDay(anyString()))
                .thenReturn(new com.pally.domain.progress.StreakService.StreakUpdateResult(0, 0, 0, 0));
    }

    private UserJpaEntity passwordAccount(String email) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId("owner-1");
        u.setEmail(email);
        u.setPasswordHash("$2a$hashed"); // has a password → DIFFERENT credential type
        u.setCreatedAt(Instant.now().minusSeconds(9999));
        return u;
    }

    private UserJpaEntity socialAccount(String email) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId("social-1");
        u.setEmail(email);
        u.setPasswordHash(null); // passwordless → a social account
        u.setCreatedAt(Instant.now().minusSeconds(9999));
        u.setLastActiveDate(LocalDate.now(com.pally.shared.util.PallyTime.SGT)); // skip streak path
        return u;
    }

    @Test
    void verifiedEmailMatchesPasswordAccount_throwsLinkRequired_neverIssuesToken() {
        when(userRepo.findByEmail("victim@x.com")).thenReturn(Optional.of(passwordAccount("victim@x.com")));

        assertThatThrownBy(() ->
                authService.signInWithSocial("victim@x.com", true, "Attacker", "google", "goog-sub-1", true))
                .isInstanceOf(LinkRequiredException.class)
                .satisfies(e -> {
                    assertThat(((LinkRequiredException) e).getChallenge()).isEqualTo("PASSWORD");
                    assertThat(((LinkRequiredException) e).getProvider()).isEqualTo("google");
                });

        verify(jwtService, never()).generateToken(anyString(), any(), anyInt()); // NO token
        verify(userRepo, never()).save(any());                                    // NO account
    }

    @Test
    void unverifiedEmail_neverMatches_doesNotLinkToPasswordAccount() {
        lenient().when(userRepo.findByEmail(anyString()))
                .thenReturn(Optional.of(passwordAccount("victim@x.com")));

        // email_verified=false → must NOT match the victim. It used to fall through
        // and create a standalone account; since self-serve social signup closed it
        // is refused instead. The SECURITY property under test is unchanged and is
        // what matters: an attacker-controllable unverified email never reaches the
        // victim's row, and now cannot mint an account either.
        assertThatThrownBy(() ->
                authService.signInWithSocial(
                        "victim@x.com", false, "Someone", "google", "goog-sub-2", true))
                .isInstanceOf(com.pally.shared.exception.BusinessException.class);

        verify(userRepo, never()).save(any()); // the victim row is never touched
    }

    @Test
    void subKeyMatch_logsInReturningUser_withoutEmailLookup() {
        UserJpaEntity social = socialAccount("returning@x.com");
        when(userRepo.findByProviderAndProviderSub("google", "goog-sub-3"))
                .thenReturn(Optional.of(social));

        var res = authService.signInWithSocial("changed@x.com", true, null, "google", "goog-sub-3", true);

        assertThat(res.userId()).isEqualTo("social-1");
        verify(userRepo, never()).findByEmail(anyString()); // sub is the key — email irrelevant
        verify(userRepo, never()).save(any());
    }

    @Test
    void legacyEmailRow_noSub_isLazilyBackfilled_thenSubKeyed() {
        UserJpaEntity legacy = socialAccount("legacy@x.com"); // provider/sub null
        when(userRepo.findByEmail("legacy@x.com")).thenReturn(Optional.of(legacy));
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        authService.signInWithSocial("legacy@x.com", true, null, "google", "goog-sub-4", true);

        ArgumentCaptor<UserJpaEntity> saved = ArgumentCaptor.forClass(UserJpaEntity.class);
        verify(userRepo).save(saved.capture()); // stamped
        assertThat(saved.getValue().getProvider()).isEqualTo("google");
        assertThat(saved.getValue().getProviderSub()).isEqualTo("goog-sub-4");
    }

    /**
     * REWRITTEN, NOT DELETED — this used to assert a NEW social account is created
     * sub-keyed. Self-serve social signup is closed (the web is invite-only), so the
     * contract inverted: an unknown Google account must be REFUSED. Kept under the
     * same name because it guards the same branch, now from the other side.
     *
     * <p>This branch mattered more than the /signup page: the memoly LOGIN page also
     * calls /auth/google, so an unknown account could sign up from a page that never
     * mentions signing up.
     */
    @Test
    void newSocialUser_isRefused_signupIsClosed() {
        when(userRepo.findByEmail("fresh@x.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                authService.signInWithSocial(
                        "fresh@x.com", true, "Fresh", "google", "goog-sub-5", true))
                .isInstanceOf(com.pally.shared.exception.BusinessException.class)
                .hasMessageContaining("invite-only");

        verify(userRepo, never()).save(any());
    }

    // ── acceptedTerms gate ───────────────────────────────────────────────
    // Pins the DEFERRED.md-closed gap: signInWithSocial previously created accounts
    // on first sign-in with NO terms gate at all. Only a genuinely NEW account is
    // gated — a returning user (sub or verified-email match) must never be blocked
    // by this, since a login-page social button has no reason to show a checkbox.

    @Test
    void newSocialUser_termsNotAccepted_rejects400_noAccountCreated() {
        when(userRepo.findByEmail("fresh2@x.com")).thenReturn(Optional.empty());

        // Still refused, and still creates nothing / mints no token — the invariant
        // this test exists for. The REASON changed: signup closure is now checked
        // before the terms gate, so the message is the invite-only one. Asserting the
        // old "Terms of Use" text would pin a reason that no longer applies while the
        // property it protects (no account, no token) is what actually matters.
        assertThatThrownBy(() ->
                authService.signInWithSocial(
                        "fresh2@x.com", true, "Fresh", "google", "goog-sub-6", false))
                .isInstanceOf(com.pally.shared.exception.BusinessException.class);

        verify(userRepo, never()).save(any());
        verify(consentService, never()).recordTermsAcceptance(any());
        verify(jwtService, never()).generateToken(anyString(), any(), anyInt());
    }

    /**
     * REWRITTEN, NOT DELETED. Previously: accepting terms creates the account and
     * records consent. Now that self-serve social signup is closed, accepting terms
     * must NOT buy an account — and no consent row may be written for an account
     * that was never created (an orphaned consent row for a nonexistent user is the
     * exact defect the original consent-ordering work fixed).
     */
    @Test
    void newSocialUser_termsAccepted_stillCreatesNothing_andRecordsNoConsent() {
        when(userRepo.findByEmail("fresh3@x.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                authService.signInWithSocial(
                        "fresh3@x.com", true, "Fresh", "google", "goog-sub-7", true))
                .isInstanceOf(com.pally.shared.exception.BusinessException.class);

        verify(userRepo, never()).save(any());
        verify(consentService, never()).recordTermsAcceptance(any());
    }

    @Test
    void returningSocialUser_subKeyMatch_neverGatedOnTerms() {
        // A returning user's login must succeed regardless of acceptedTerms — the
        // gate only applies to account CREATION.
        UserJpaEntity social = socialAccount("returning2@x.com");
        when(userRepo.findByProviderAndProviderSub("google", "goog-sub-8"))
                .thenReturn(Optional.of(social));

        var res = authService.signInWithSocial(
                "returning2@x.com", true, null, "google", "goog-sub-8", false);

        assertThat(res.userId()).isEqualTo("social-1");
        verify(userRepo, never()).save(any());
        verify(consentService, never()).recordTermsAcceptance(any());
    }
}
