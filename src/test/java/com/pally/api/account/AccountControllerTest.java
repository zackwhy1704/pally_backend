package com.pally.api.account;

import com.pally.domain.account.AccountType;
import com.pally.domain.account.usecase.DeleteAccountUseCase;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.infrastructure.auth.JwtService;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.pally.shared.exception.UpgradeRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock UserJpaRepository userRepo;
    @Mock PremiumService premiumService;
    @Mock DeleteAccountUseCase deleteAccountUseCase;
    @Mock JwtService jwtService;
    @Mock SlidingWindowRateLimiter rateLimiter;

    @InjectMocks AccountController controller;

    private static final String PARENT = "parent-1";
    private static final String CODE = "ABC234";

    @BeforeEach
    void allowRateLimit() {
        lenient().when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(new SlidingWindowRateLimiter.Result(true, 0));
    }

    private UserJpaEntity parent() {
        UserJpaEntity p = new UserJpaEntity();
        p.setId(PARENT);
        p.setAccountType(AccountType.SOLO);
        return p;
    }

    private UserJpaEntity unlinkedChild() {
        UserJpaEntity c = new UserJpaEntity();
        c.setId("child-1");
        c.setLinkCode(CODE);
        // parentId null, no expiry → a valid, claimable code
        return c;
    }

    @Test
    void claim_freeParentAtCap_returnsUpgradeRequired_andDoesNotLinkChild() {
        UserJpaEntity child = unlinkedChild();
        when(userRepo.findById(PARENT)).thenReturn(Optional.of(parent()));
        when(userRepo.findByLinkCode(CODE)).thenReturn(Optional.of(child));
        when(premiumService.resolveTier(PARENT)).thenReturn(SubscriptionTier.FREE);
        when(userRepo.countByParentId(PARENT)).thenReturn(1); // FREE cap == 1, already at it

        assertThatThrownBy(() -> controller.claim(PARENT, Map.of("code", CODE)))
                .isInstanceOf(UpgradeRequiredException.class);

        // Atomic rejection: the child is never linked or mutated, nothing saved.
        assertThat(child.getParentId()).isNull();
        verify(userRepo, never()).save(child);
    }

    @Test
    void claim_freeParentUnderCap_linksChild() {
        UserJpaEntity child = unlinkedChild();
        when(userRepo.findById(PARENT)).thenReturn(Optional.of(parent()));
        when(userRepo.findByLinkCode(CODE)).thenReturn(Optional.of(child));
        when(premiumService.resolveTier(PARENT)).thenReturn(SubscriptionTier.FREE);
        when(userRepo.countByParentId(PARENT)).thenReturn(0); // under the cap

        controller.claim(PARENT, Map.of("code", CODE));

        assertThat(child.getParentId()).isEqualTo(PARENT);
        assertThat(child.getAccountType()).isEqualTo(AccountType.CHILD);
        verify(userRepo).save(child);
    }
}
