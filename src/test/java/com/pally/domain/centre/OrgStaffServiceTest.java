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
class OrgStaffServiceTest {

    @Mock CentreAccessService accessService;
    @Mock OrgStaffJpaRepository staffRepo;
    @Mock OrganizationJpaRepository orgRepo;
    @Mock UserJpaRepository userRepo;
    @Mock CentreInviteTokenJpaRepository inviteRepo;

    @InjectMocks OrgStaffService service;

    private static final String OWNER_ID = "owner-1";
    private static final String STAFF_ID = "staff-1";
    private static final String ORG_ID   = "org-1";

    private OrganizationJpaEntity org() {
        OrganizationJpaEntity o = new OrganizationJpaEntity();
        o.setId(ORG_ID);
        o.setName("Test Centre");
        o.setOwnerUserId(OWNER_ID);
        return o;
    }

    private UserJpaEntity user(String id, String email) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(id);
        u.setEmail(email);
        u.setDisplayName("Test User");
        return u;
    }

    @BeforeEach
    void setUp() {
        lenient().when(staffRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(inviteRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── listStaff ─────────────────────────────────────────────────────────────

    @Test
    void listStaff_includesOwnerThenStaffRows() {
        when(accessService.ensureStaff(OWNER_ID, ORG_ID)).thenReturn(org());
        when(userRepo.findById(OWNER_ID)).thenReturn(Optional.of(user(OWNER_ID, "owner@test.com")));

        OrgStaffJpaEntity staffRow = new OrgStaffJpaEntity();
        staffRow.setUserId(STAFF_ID);
        staffRow.setOrgId(ORG_ID);
        when(staffRepo.findByOrgIdAndStatus(ORG_ID, OrgStaffJpaEntity.STATUS_ACTIVE))
                .thenReturn(List.of(staffRow));
        when(userRepo.findById(STAFF_ID)).thenReturn(Optional.of(user(STAFF_ID, "staff@test.com")));

        List<Map<String, Object>> result = service.listStaff(OWNER_ID, ORG_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("role")).isEqualTo("OWNER");
        assertThat(result.get(1).get("role")).isEqualTo("STAFF");
    }

    // ── inviteStaff — direct-attach path ─────────────────────────────────────

    @Test
    void inviteStaff_existingUser_attachesDirectly() {
        when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org());
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(org()));
        when(userRepo.findByEmail("staff@test.com")).thenReturn(Optional.of(user(STAFF_ID, "staff@test.com")));
        when(staffRepo.existsByOrgIdAndUserIdAndStatus(ORG_ID, STAFF_ID, OrgStaffJpaEntity.STATUS_ACTIVE))
                .thenReturn(false);
        when(staffRepo.findByOrgIdAndUserId(ORG_ID, STAFF_ID)).thenReturn(Optional.empty());

        Map<String, Object> result = service.inviteStaff(OWNER_ID, ORG_ID, "staff@test.com");

        assertThat(result.get("attached")).isEqualTo(true);
        assertThat(result.get("userId")).isEqualTo(STAFF_ID);
        verify(staffRepo).save(any(OrgStaffJpaEntity.class));
        verify(inviteRepo, never()).save(any());
    }

    @Test
    void inviteStaff_existingUserAlreadyActive_throws409() {
        when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org());
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(org()));
        when(userRepo.findByEmail("staff@test.com")).thenReturn(Optional.of(user(STAFF_ID, "staff@test.com")));
        when(staffRepo.existsByOrgIdAndUserIdAndStatus(ORG_ID, STAFF_ID, OrgStaffJpaEntity.STATUS_ACTIVE))
                .thenReturn(true);

        assertThatThrownBy(() -> service.inviteStaff(OWNER_ID, ORG_ID, "staff@test.com"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 409);
    }

    // ── inviteStaff — token path ──────────────────────────────────────────────

    @Test
    void inviteStaff_unknownEmail_createsInviteToken() {
        when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org());
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(org()));
        when(userRepo.findByEmail("new@test.com")).thenReturn(Optional.empty());

        Map<String, Object> result = service.inviteStaff(OWNER_ID, ORG_ID, "new@test.com");

        assertThat(result.get("attached")).isEqualTo(false);
        assertThat(result.get("token")).isNotNull();
        ArgumentCaptor<CentreInviteTokenJpaEntity> cap =
                ArgumentCaptor.forClass(CentreInviteTokenJpaEntity.class);
        verify(inviteRepo).save(cap.capture());
        assertThat(cap.getValue().getRole()).isEqualTo("STAFF");
        assertThat(cap.getValue().getOrgId()).isEqualTo(ORG_ID);
    }

    @Test
    void inviteStaff_nullEmail_throws400() {
        when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org());

        assertThatThrownBy(() -> service.inviteStaff(OWNER_ID, ORG_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);
        verify(staffRepo, never()).save(any());
    }

    // ── removeStaff ───────────────────────────────────────────────────────────

    @Test
    void removeStaff_activeStaff_setsRemovedStatus() {
        OrgStaffJpaEntity staffRow = new OrgStaffJpaEntity();
        staffRow.setUserId(STAFF_ID);
        staffRow.setOrgId(ORG_ID);
        staffRow.setStatus(OrgStaffJpaEntity.STATUS_ACTIVE);

        when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org());
        when(staffRepo.findByOrgIdAndUserId(ORG_ID, STAFF_ID)).thenReturn(Optional.of(staffRow));

        service.removeStaff(OWNER_ID, ORG_ID, STAFF_ID);

        ArgumentCaptor<OrgStaffJpaEntity> cap = ArgumentCaptor.forClass(OrgStaffJpaEntity.class);
        verify(staffRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(OrgStaffJpaEntity.STATUS_REMOVED);
        assertThat(cap.getValue().getRemovedAt()).isNotNull();
    }

    @Test
    void removeStaff_alreadyRemoved_isIdempotent() {
        OrgStaffJpaEntity staffRow = new OrgStaffJpaEntity();
        staffRow.setUserId(STAFF_ID);
        staffRow.setStatus(OrgStaffJpaEntity.STATUS_REMOVED);

        when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org());
        when(staffRepo.findByOrgIdAndUserId(ORG_ID, STAFF_ID)).thenReturn(Optional.of(staffRow));

        service.removeStaff(OWNER_ID, ORG_ID, STAFF_ID);

        verify(staffRepo, never()).save(any());
    }

    @Test
    void removeStaff_ownerRemovingSelf_throws400() {
        when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org());

        assertThatThrownBy(() -> service.removeStaff(OWNER_ID, ORG_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);
    }

    @Test
    void removeStaff_notFound_throws404() {
        when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org());
        when(staffRepo.findByOrgIdAndUserId(ORG_ID, "unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeStaff(OWNER_ID, ORG_ID, "unknown"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
    }
}
