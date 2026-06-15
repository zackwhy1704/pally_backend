package com.pally.domain.account;

import com.pally.domain.subscription.Entitlements;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.UpgradeRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock UserRepository userRepo;
    @Mock PremiumService premiumService;
    @Mock ClaimRateLimiter claimRateLimiter;

    AccountService service;

    private static final String PARENT_ID = "parent-1";
    private static final String CHILD_ID = "child-1";
    private static final String CODE = "XYZ789";

    @BeforeEach
    void setUp() {
        service = new AccountService(userRepo, premiumService, claimRateLimiter);
        lenient().when(claimRateLimiter.tryAcquire(anyString()))
                .thenReturn(new ClaimRateLimiter.Result(true, 0));
    }

    // ── FCM token ──────────────────────────────────────────────────────────

    @Test
    void setFcmToken_callsRepositorySetFcmToken() {
        service.setFcmToken("user-1", "fcm-token-abc");
        verify(userRepo).setFcmToken("user-1", "fcm-token-abc");
    }

    // ── Link-code ──────────────────────────────────────────────────────────

    @Test
    void issueLinkCode_parentAccount_throws409() {
        User parent = soloUser(PARENT_ID);
        parent.setAccountType(AccountType.PARENT);
        when(userRepo.findById(PARENT_ID)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.issueLinkCode(PARENT_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Parents can't generate");
    }

    @Test
    void issueLinkCode_alreadyLinkedChild_throws409() {
        User child = soloUser(CHILD_ID);
        child.setParentId(PARENT_ID); // already linked
        when(userRepo.findById(CHILD_ID)).thenReturn(Optional.of(child));

        assertThatThrownBy(() -> service.issueLinkCode(CHILD_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already linked");
    }

    @Test
    void issueLinkCode_unlinkedSoloUser_returnsCodeAndTtl() {
        User solo = soloUser(CHILD_ID);
        when(userRepo.findById(CHILD_ID)).thenReturn(Optional.of(solo));
        when(userRepo.findByLinkCode(anyString())).thenReturn(Optional.empty());

        Map<String, Object> result = service.issueLinkCode(CHILD_ID);

        assertThat(result).containsKey("code");
        assertThat(result).containsKey("expiresAt");
        assertThat(result.get("ttlSeconds")).isEqualTo(900L); // 15 min
        verify(userRepo).setLinkCode(anyString(), anyString(), any(Instant.class));
    }

    // ── Claim ──────────────────────────────────────────────────────────────

    @Test
    void claim_nullCode_throws400() {
        assertThatThrownBy(() -> service.claim(PARENT_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("code is required");
    }

    @Test
    void claim_childAccountAsClaimer_throws403() {
        User child = soloUser(PARENT_ID);
        child.setAccountType(AccountType.CHILD);
        when(userRepo.findById(PARENT_ID)).thenReturn(Optional.of(child));

        assertThatThrownBy(() -> service.claim(PARENT_ID, CODE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Child accounts can't claim");
    }

    @Test
    void claim_expiredCode_throws410_andClearsCode() {
        User parent = soloUser(PARENT_ID);
        User child = soloUser(CHILD_ID);
        child.setLinkCode(CODE);
        child.setLinkCodeExpiresAt(Instant.now().minusSeconds(60)); // expired

        when(userRepo.findById(PARENT_ID)).thenReturn(Optional.of(parent));
        when(userRepo.findByLinkCode(CODE)).thenReturn(Optional.of(child));

        assertThatThrownBy(() -> service.claim(PARENT_ID, CODE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
        verify(userRepo).clearLinkCode(CHILD_ID);
    }

    @Test
    void claim_atSubscriptionCap_throwsUpgradeRequired() {
        User parent = soloUser(PARENT_ID);
        User child = soloUser(CHILD_ID);
        child.setLinkCode(CODE);

        when(userRepo.findById(PARENT_ID)).thenReturn(Optional.of(parent));
        when(userRepo.findByLinkCode(CODE)).thenReturn(Optional.of(child));
        when(premiumService.resolveTier(PARENT_ID)).thenReturn(SubscriptionTier.FREE);
        when(userRepo.countByParentId(PARENT_ID))
                .thenReturn(Entitlements.forTier(SubscriptionTier.FREE).maxStudents());

        assertThatThrownBy(() -> service.claim(PARENT_ID, CODE))
                .isInstanceOf(UpgradeRequiredException.class);
        verify(userRepo, never()).save(child);
    }

    @Test
    void claim_validCode_underCap_linksChildAndPromotesParent() {
        User parent = soloUser(PARENT_ID);
        User child = soloUser(CHILD_ID);
        child.setLinkCode(CODE);

        when(userRepo.findById(PARENT_ID)).thenReturn(Optional.of(parent));
        when(userRepo.findByLinkCode(CODE)).thenReturn(Optional.of(child));
        when(premiumService.resolveTier(PARENT_ID)).thenReturn(SubscriptionTier.FREE);
        when(userRepo.countByParentId(PARENT_ID)).thenReturn(0);
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.claim(PARENT_ID, CODE);

        assertThat(child.getParentId()).isEqualTo(PARENT_ID);
        assertThat(child.getAccountType()).isEqualTo(AccountType.CHILD);
        assertThat(child.getLinkCode()).isNull();
        assertThat(parent.getAccountType()).isEqualTo(AccountType.PARENT);
        assertThat(result.get("childId")).isEqualTo(CHILD_ID);
        verify(claimRateLimiter).reset(PARENT_ID);
    }

    @Test
    void claim_rateLimited_throws429() {
        when(claimRateLimiter.tryAcquire(PARENT_ID))
                .thenReturn(new ClaimRateLimiter.Result(false, 42));

        assertThatThrownBy(() -> service.claim(PARENT_ID, CODE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many");
    }

    // ── Upgrade to parent ──────────────────────────────────────────────────

    @Test
    void upgradeToParent_childAccount_throws403() {
        User child = soloUser(PARENT_ID);
        child.setAccountType(AccountType.CHILD);
        when(userRepo.findById(PARENT_ID)).thenReturn(Optional.of(child));

        assertThatThrownBy(() -> service.upgradeToParent(PARENT_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Child accounts can't be upgraded");
    }

    @Test
    void upgradeToParent_soloAccount_setsParentType() {
        User solo = soloUser(PARENT_ID);
        when(userRepo.findById(PARENT_ID)).thenReturn(Optional.of(solo));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.upgradeToParent(PARENT_ID);

        assertThat(solo.getAccountType()).isEqualTo(AccountType.PARENT);
        assertThat(result.get("accountType")).isEqualTo(AccountType.PARENT);
    }

    // ── Family ─────────────────────────────────────────────────────────────

    @Test
    void getFamily_childAccount_returnsParentInfo() {
        User child = soloUser(CHILD_ID);
        child.setAccountType(AccountType.CHILD);
        child.setParentId(PARENT_ID);
        User parent = soloUser(PARENT_ID);
        parent.setDisplayName("Test Parent");

        when(userRepo.findById(CHILD_ID)).thenReturn(Optional.of(child));
        when(userRepo.findById(PARENT_ID)).thenReturn(Optional.of(parent));

        Map<String, Object> result = service.getFamily(CHILD_ID);

        assertThat(result.get("accountType")).isEqualTo(AccountType.CHILD);
        @SuppressWarnings("unchecked")
        Map<String, Object> parentMap = (Map<String, Object>) result.get("parent");
        assertThat(parentMap.get("displayName")).isEqualTo("Test Parent");
    }

    @Test
    void getFamily_parentAccount_returnsChildrenList() {
        User parent = soloUser(PARENT_ID);
        parent.setAccountType(AccountType.PARENT);
        User child1 = soloUser(CHILD_ID);
        child1.setParentId(PARENT_ID);

        when(userRepo.findById(PARENT_ID)).thenReturn(Optional.of(parent));
        when(userRepo.findByParentId(PARENT_ID)).thenReturn(List.of(child1));

        Map<String, Object> result = service.getFamily(PARENT_ID);

        assertThat(result.get("accountType")).isEqualTo(AccountType.PARENT);
        @SuppressWarnings("unchecked")
        List<?> children = (List<?>) result.get("children");
        assertThat(children).hasSize(1);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private User soloUser(String id) {
        User u = new User();
        u.setId(id);
        u.setAccountType(AccountType.SOLO);
        return u;
    }
}
