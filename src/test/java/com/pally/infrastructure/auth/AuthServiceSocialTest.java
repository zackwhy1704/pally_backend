package com.pally.infrastructure.auth;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.LinkRequiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 3a-core: social sign-in must NEVER silently auto-link to an existing PASSWORD account
 * (the account-takeover vector — a social token bearing a victim's email logged into
 * their password account). And an UNVERIFIED provider email is attacker-controllable, so
 * it must never match/link at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceSocialTest {

    @Mock UserJpaRepository userRepo;
    @Mock JwtService jwtService;
    @Mock com.pally.domain.shop.CharacterShopService characterShopService;
    @Mock com.pally.domain.progress.BadgeService badgeService;
    @Mock com.pally.domain.progress.StreakService streakService;
    @InjectMocks AuthService authService;

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
                authService.signInWithSocial("victim@x.com", true, "Attacker", "google"))
                .isInstanceOf(LinkRequiredException.class)
                .satisfies(e -> {
                    assertThat(((LinkRequiredException) e).getChallenge()).isEqualTo("PASSWORD");
                    assertThat(((LinkRequiredException) e).getProvider()).isEqualTo("google");
                });

        verify(jwtService, never()).generateToken(anyString(), any()); // NO token minted
        verify(userRepo, never()).save(any());                          // NO account created
    }

    @Test
    void unverifiedEmail_neverMatches_doesNotLinkToPasswordAccount() {
        // email_verified=false → must NOT even look at the existing account: no takeover.
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateToken(anyString(), any())).thenReturn("tok");
        when(streakService.recordActiveDay(anyString()))
                .thenReturn(new com.pally.domain.progress.StreakService.StreakUpdateResult(0, 0, 0, 0));
        lenient().when(userRepo.findByEmail(anyString()))
                .thenReturn(Optional.of(passwordAccount("victim@x.com")));

        // Does not throw LinkRequired and does not log into the victim — creates standalone.
        authService.signInWithSocial("victim@x.com", false, "Someone", "google");

        verify(userRepo).save(any()); // a NEW standalone account, not the victim's
    }

    @Test
    void verifiedEmailMatchesSocialAccount_logsInReturningUser() {
        when(userRepo.findByEmail("returning@x.com")).thenReturn(Optional.of(socialAccount("returning@x.com")));
        when(jwtService.generateToken(anyString(), any())).thenReturn("tok-return");

        var res = authService.signInWithSocial("returning@x.com", true, null, "google");

        assertThat(res.userId()).isEqualTo("social-1");
        assertThat(res.token()).isEqualTo("tok-return");
        verify(userRepo, never()).save(any()); // returning user — no new account
    }
}
