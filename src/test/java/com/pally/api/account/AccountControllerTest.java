package com.pally.api.account;

import com.pally.domain.account.AccountService;
import com.pally.domain.account.AccountType;
import com.pally.domain.account.ClaimRateLimiter;
import com.pally.domain.account.usecase.DeleteAccountUseCase;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.auth.JwtService;
import com.pally.shared.exception.UpgradeRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountService} — claim flow, rate-limit, and family-cap.
 * The controller itself is now a thin delegator with no logic.
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock UserRepository userRepo;
    @Mock PremiumService premiumService;
    @Mock ClaimRateLimiter claimRateLimiter;
    @Mock DeleteAccountUseCase deleteAccountUseCase;
    @Mock JwtService jwtService;

    AccountService service;

    private static final String PARENT = "parent-1";
    private static final String CODE = "ABC234";

    @BeforeEach
    void setUp() {
        service = new AccountService(userRepo, premiumService, claimRateLimiter);
        lenient().when(claimRateLimiter.tryAcquire(anyString()))
                .thenReturn(new ClaimRateLimiter.Result(true, 0));
    }

    private User parent() {
        User p = new User();
        p.setId(PARENT);
        p.setAccountType(AccountType.SOLO);
        return p;
    }

    private User unlinkedChild() {
        User c = new User();
        c.setId("child-1");
        c.setLinkCode(CODE);
        // parentId null, no expiry → a valid, claimable code
        return c;
    }

    @Test
    void claim_freeParentAtCap_returnsUpgradeRequired_andDoesNotLinkChild() {
        User child = unlinkedChild();
        when(userRepo.findById(PARENT)).thenReturn(Optional.of(parent()));
        when(userRepo.findByLinkCode(CODE)).thenReturn(Optional.of(child));
        when(premiumService.resolveTier(PARENT)).thenReturn(SubscriptionTier.FREE);
        when(userRepo.countByParentId(PARENT)).thenReturn(1); // FREE cap == 1, already at it

        assertThatThrownBy(() -> service.claim(PARENT, CODE))
                .isInstanceOf(UpgradeRequiredException.class);

        // Atomic rejection: the child is never linked or mutated, nothing saved.
        assertThat(child.getParentId()).isNull();
        verify(userRepo, never()).save(child);
    }

    @Test
    void claim_freeParentUnderCap_linksChild() {
        User child = unlinkedChild();
        when(userRepo.findById(PARENT)).thenReturn(Optional.of(parent()));
        when(userRepo.findByLinkCode(CODE)).thenReturn(Optional.of(child));
        when(premiumService.resolveTier(PARENT)).thenReturn(SubscriptionTier.FREE);
        when(userRepo.countByParentId(PARENT)).thenReturn(0); // under the cap

        service.claim(PARENT, CODE);

        assertThat(child.getParentId()).isEqualTo(PARENT);
        assertThat(child.getAccountType()).isEqualTo(AccountType.CHILD);
        verify(userRepo).save(child);
    }
}
