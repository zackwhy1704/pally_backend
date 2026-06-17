package com.pally.domain.centre;

import com.pally.infrastructure.persistence.organization.CentreInviteTokenJpaEntity;
import com.pally.infrastructure.persistence.organization.CentreInviteTokenJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CentreInviteServiceTest {

    @Mock CentreInviteTokenJpaRepository inviteRepo;
    @Mock OrganizationJpaRepository orgRepo;
    @Mock OrgStaffJpaRepository staffRepo;
    @Mock UserJpaRepository userRepo;

    @InjectMocks CentreInviteService service;

    private static final String ADMIN_ID    = "admin-1";
    private static final String OWNER_ID    = "owner-1";
    private static final String TOKEN       = "abc123def456";

    private CentreInviteTokenJpaEntity validInvite() {
        CentreInviteTokenJpaEntity inv = new CentreInviteTokenJpaEntity();
        inv.setToken(TOKEN);
        inv.setCentreName("Bright Stars Tuition");
        inv.setContactEmail("owner@example.com");
        inv.setCreatedBy(ADMIN_ID);
        inv.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        return inv;
    }

    @BeforeEach
    void setUp() {
        lenient().when(inviteRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orgRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(staffRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── createInvite ──────────────────────────────────────────────────────────

    @Test
    void createInvite_validBody_persistsTokenAndReturnsIt() {
        Map<String, Object> result = service.createInvite(ADMIN_ID,
                Map.of("centreName", "Bright Stars", "contactEmail", "owner@example.com"));

        assertThat(result.get("token")).isNotNull();
        assertThat(result.get("centreName")).isEqualTo("Bright Stars");
        ArgumentCaptor<CentreInviteTokenJpaEntity> cap =
                ArgumentCaptor.forClass(CentreInviteTokenJpaEntity.class);
        verify(inviteRepo).save(cap.capture());
        assertThat(cap.getValue().getCreatedBy()).isEqualTo(ADMIN_ID);
    }

    @Test
    void createInvite_missingCentreName_throws400() {
        assertThatThrownBy(() -> service.createInvite(ADMIN_ID,
                Map.of("contactEmail", "a@b.com")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);
    }

    // ── getInvite ─────────────────────────────────────────────────────────────

    @Test
    void getInvite_validToken_returnsCentreNameAndRole() {
        when(inviteRepo.findById(TOKEN)).thenReturn(Optional.of(validInvite()));

        Map<String, Object> result = service.getInvite(TOKEN);

        assertThat(result.get("centreName")).isEqualTo("Bright Stars Tuition");
        assertThat(result.get("role")).isEqualTo("OWNER");
        assertThat(result).doesNotContainKey("contactEmail");
    }

    @Test
    void getInvite_unknownToken_throws404() {
        when(inviteRepo.findById("bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInvite("bad"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
    }

    @Test
    void getInvite_expiredToken_throws410() {
        CentreInviteTokenJpaEntity expired = validInvite();
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(inviteRepo.findById(TOKEN)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.getInvite(TOKEN))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 410);
    }

    // ── acceptInvite ──────────────────────────────────────────────────────────

    @Test
    void acceptInvite_validToken_createsOrgAndClearsToken() {
        when(inviteRepo.findById(TOKEN)).thenReturn(Optional.of(validInvite()));
        when(orgRepo.findFirstByOwnerUserId(OWNER_ID)).thenReturn(Optional.empty());
        UserJpaEntity user = new UserJpaEntity();
        user.setId(OWNER_ID);
        when(userRepo.findById(OWNER_ID)).thenReturn(Optional.of(user));

        Map<String, Object> result = service.acceptInvite(OWNER_ID, Map.of("token", TOKEN));

        assertThat(result.get("created")).isEqualTo(true);
        assertThat(result.get("orgId")).isNotNull();
        verify(orgRepo).save(any(OrganizationJpaEntity.class));
        ArgumentCaptor<CentreInviteTokenJpaEntity> cap =
                ArgumentCaptor.forClass(CentreInviteTokenJpaEntity.class);
        verify(inviteRepo).save(cap.capture());
        assertThat(cap.getValue().getAcceptedBy()).isEqualTo(OWNER_ID);
        assertThat(cap.getValue().getAcceptedAt()).isNotNull();
    }

    @Test
    void acceptInvite_userAlreadyOwnsOrg_throws409() {
        when(inviteRepo.findById(TOKEN)).thenReturn(Optional.of(validInvite()));
        when(orgRepo.findFirstByOwnerUserId(OWNER_ID))
                .thenReturn(Optional.of(new OrganizationJpaEntity()));

        assertThatThrownBy(() -> service.acceptInvite(OWNER_ID, Map.of("token", TOKEN)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 409);
        verify(orgRepo, never()).save(any());
    }

    @Test
    void acceptInvite_alreadyAccepted_throws409() {
        CentreInviteTokenJpaEntity used = validInvite();
        used.setAcceptedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        used.setAcceptedBy("other-user");
        when(inviteRepo.findById(TOKEN)).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> service.acceptInvite(OWNER_ID, Map.of("token", TOKEN)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 409);
    }

    // ── Staff invite (STAFF role) ─────────────────────────────────────────────

    @Test
    void acceptInvite_staffRole_createsOrgStaffRow() {
        String staffUserId = "staff-1";
        String orgId = "org-abc";

        CentreInviteTokenJpaEntity staffInvite = validInvite();
        staffInvite.setRole("STAFF");
        staffInvite.setOrgId(orgId);

        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId(orgId);
        org.setName("Bright Stars Tuition");

        UserJpaEntity user = new UserJpaEntity();
        user.setId(staffUserId);

        when(inviteRepo.findById(TOKEN)).thenReturn(Optional.of(staffInvite));
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        when(staffRepo.existsByOrgIdAndUserIdAndStatus(orgId, staffUserId, OrgStaffJpaEntity.STATUS_ACTIVE))
                .thenReturn(false);
        when(userRepo.findById(staffUserId)).thenReturn(Optional.of(user));

        Map<String, Object> result = service.acceptInvite(staffUserId, Map.of("token", TOKEN));

        assertThat(result.get("created")).isEqualTo(true);
        assertThat(result.get("orgId")).isEqualTo(orgId);
        verify(staffRepo).save(any(OrgStaffJpaEntity.class));
        verify(orgRepo, never()).save(any(OrganizationJpaEntity.class));
    }

    @Test
    void acceptInvite_staffRoleAlreadyActive_idempotent() {
        String staffUserId = "staff-1";
        String orgId = "org-abc";

        CentreInviteTokenJpaEntity staffInvite = validInvite();
        staffInvite.setRole("STAFF");
        staffInvite.setOrgId(orgId);

        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId(orgId);
        org.setName("Bright Stars Tuition");

        when(inviteRepo.findById(TOKEN)).thenReturn(Optional.of(staffInvite));
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        when(staffRepo.existsByOrgIdAndUserIdAndStatus(orgId, staffUserId, OrgStaffJpaEntity.STATUS_ACTIVE))
                .thenReturn(true);

        Map<String, Object> result = service.acceptInvite(staffUserId, Map.of("token", TOKEN));

        assertThat(result.get("created")).isEqualTo(false);
        verify(staffRepo, never()).save(any());
    }
}
