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
                authService.signInWithSocial("victim@x.com", true, "Attacker", "google", "goog-sub-1"))
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
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepo.findByEmail(anyString()))
                .thenReturn(Optional.of(passwordAccount("victim@x.com")));

        // email_verified=false → must NOT match the victim; creates a standalone account.
        authService.signInWithSocial("victim@x.com", false, "Someone", "google", "goog-sub-2");

        ArgumentCaptor<UserJpaEntity> saved = ArgumentCaptor.forClass(UserJpaEntity.class);
        verify(userRepo).save(saved.capture());
        assertThat(saved.getValue().getId()).isNotEqualTo("owner-1"); // NOT the victim
    }

    @Test
    void subKeyMatch_logsInReturningUser_withoutEmailLookup() {
        UserJpaEntity social = socialAccount("returning@x.com");
        when(userRepo.findByProviderAndProviderSub("google", "goog-sub-3"))
                .thenReturn(Optional.of(social));

        var res = authService.signInWithSocial("changed@x.com", true, null, "google", "goog-sub-3");

        assertThat(res.userId()).isEqualTo("social-1");
        verify(userRepo, never()).findByEmail(anyString()); // sub is the key — email irrelevant
        verify(userRepo, never()).save(any());
    }

    @Test
    void legacyEmailRow_noSub_isLazilyBackfilled_thenSubKeyed() {
        UserJpaEntity legacy = socialAccount("legacy@x.com"); // provider/sub null
        when(userRepo.findByEmail("legacy@x.com")).thenReturn(Optional.of(legacy));
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        authService.signInWithSocial("legacy@x.com", true, null, "google", "goog-sub-4");

        ArgumentCaptor<UserJpaEntity> saved = ArgumentCaptor.forClass(UserJpaEntity.class);
        verify(userRepo).save(saved.capture()); // stamped
        assertThat(saved.getValue().getProvider()).isEqualTo("google");
        assertThat(saved.getValue().getProviderSub()).isEqualTo("goog-sub-4");
    }

    @Test
    void newSocialUser_isCreatedSubKeyed() {
        when(userRepo.findByEmail("fresh@x.com")).thenReturn(Optional.empty());
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        authService.signInWithSocial("fresh@x.com", true, "Fresh", "google", "goog-sub-5");

        ArgumentCaptor<UserJpaEntity> saved = ArgumentCaptor.forClass(UserJpaEntity.class);
        verify(userRepo).save(saved.capture());
        assertThat(saved.getValue().getProvider()).isEqualTo("google");
        assertThat(saved.getValue().getProviderSub()).isEqualTo("goog-sub-5");
    }
}
