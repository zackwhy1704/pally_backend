package com.pally.domain.centre;

import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CentreAccessServiceTest {

    @Mock OrganizationJpaRepository orgRepo;
    @Mock OrgStaffJpaRepository staffRepo;
    @Mock ClassMembershipJpaRepository membershipRepo;

    @InjectMocks CentreAccessService service;

    private static final String OWNER_ID = "owner-1";
    private static final String STAFF_ID = "staff-1";
    private static final String OTHER_ID = "other-1";
    private static final String ORG_ID   = "org-1";

    private OrganizationJpaEntity org(String ownerId) {
        OrganizationJpaEntity o = new OrganizationJpaEntity();
        o.setId(ORG_ID);
        o.setOwnerUserId(ownerId);
        return o;
    }

    // ── ensureOwner ───────────────────────────────────────────────────────────

    @Test
    void ensureOwner_ownerUser_returnsOrg() {
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(org(OWNER_ID)));

        OrganizationJpaEntity result = service.ensureOwner(OWNER_ID, ORG_ID);

        assertThat(result.getId()).isEqualTo(ORG_ID);
    }

    @Test
    void ensureOwner_nonOwner_throws403() {
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(org(OWNER_ID)));

        assertThatThrownBy(() -> service.ensureOwner(STAFF_ID, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
    }

    @Test
    void ensureOwner_unknownOrg_throws404() {
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ensureOwner(OWNER_ID, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
    }

    // ── ensureStaff ───────────────────────────────────────────────────────────

    @Test
    void ensureStaff_owner_isAllowed() {
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(org(OWNER_ID)));

        OrganizationJpaEntity result = service.ensureStaff(OWNER_ID, ORG_ID);

        assertThat(result.getId()).isEqualTo(ORG_ID);
    }

    @Test
    void ensureStaff_activeStaffMember_isAllowed() {
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(org(OWNER_ID)));
        when(staffRepo.existsByOrgIdAndUserIdAndStatus(ORG_ID, STAFF_ID, OrgStaffJpaEntity.STATUS_ACTIVE))
                .thenReturn(true);

        OrganizationJpaEntity result = service.ensureStaff(STAFF_ID, ORG_ID);

        assertThat(result.getId()).isEqualTo(ORG_ID);
    }

    @Test
    void ensureStaff_neitherOwnerNorStaff_throws403() {
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(org(OWNER_ID)));
        when(staffRepo.existsByOrgIdAndUserIdAndStatus(ORG_ID, OTHER_ID, OrgStaffJpaEntity.STATUS_ACTIVE))
                .thenReturn(false);

        assertThatThrownBy(() -> service.ensureStaff(OTHER_ID, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
    }

    @Test
    void ensureStaff_unknownOrg_throws404() {
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ensureStaff(OWNER_ID, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
    }

    // ── isActiveClassMember ─────────────────────────────────────────────────────

    private ClassMembershipJpaEntity membership(String status) {
        ClassMembershipJpaEntity m = new ClassMembershipJpaEntity();
        m.setClassId("class-1");
        m.setUserId("student-1");
        m.setStatus(status);
        return m;
    }

    @Test
    void isActiveClassMember_activeMember_returnsTrue() {
        when(membershipRepo.findByClassIdAndUserId("class-1", "student-1"))
                .thenReturn(Optional.of(membership(ClassMembershipJpaEntity.STATUS_ACTIVE)));

        assertThat(service.isActiveClassMember("student-1", "class-1")).isTrue();
    }

    @Test
    void isActiveClassMember_inactiveMember_returnsFalse() {
        when(membershipRepo.findByClassIdAndUserId("class-1", "student-1"))
                .thenReturn(Optional.of(membership("REMOVED")));

        assertThat(service.isActiveClassMember("student-1", "class-1")).isFalse();
    }

    @Test
    void isActiveClassMember_noMembership_returnsFalse() {
        when(membershipRepo.findByClassIdAndUserId("class-1", "student-1"))
                .thenReturn(Optional.empty());

        assertThat(service.isActiveClassMember("student-1", "class-1")).isFalse();
    }

    @Test
    void isActiveClassMember_nullClassId_returnsFalse() {
        assertThat(service.isActiveClassMember("student-1", null)).isFalse();
    }
}
