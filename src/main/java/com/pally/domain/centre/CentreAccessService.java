package com.pally.domain.centre;

import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Shared access guard for centre-scoped admin operations.
 * {@link #ensureOwner} — owner-only (billing, staff management, org delete).
 * {@link #ensureStaff} — owner OR active org_staff row (day-to-day teaching).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentreAccessService {

    private final OrganizationJpaRepository orgRepo;
    private final OrgStaffJpaRepository staffRepo;
    private final ClassMembershipJpaRepository membershipRepo;
    private final OrgClassJpaRepository orgClassRepo;
    private final UserJpaRepository userRepo;

    /**
     * Returns the org after verifying the caller is the owner.
     * Throws 404 if org doesn't exist, 403 if caller is not the owner.
     */
    public OrganizationJpaEntity ensureOwner(String userId, String orgId) {
        OrganizationJpaEntity org = orgRepo.findById(orgId)
                .orElseThrow(() -> new BusinessException("Organization not found", 404));
        if (!org.getOwnerUserId().equals(userId)) {
            throw new BusinessException("You don't own this organization", 403);
        }
        return org;
    }

    /**
     * Returns the org after verifying the caller is the owner OR an active staff member.
     * Throws 404 if org doesn't exist, 403 if neither condition holds.
     */
    public OrganizationJpaEntity ensureStaff(String userId, String orgId) {
        OrganizationJpaEntity org = orgRepo.findById(orgId)
                .orElseThrow(() -> new BusinessException("Organization not found", 404));
        boolean isOwner = org.getOwnerUserId().equals(userId);
        boolean isStaff = staffRepo.existsByOrgIdAndUserIdAndStatus(
                orgId, userId, OrgStaffJpaEntity.STATUS_ACTIVE);
        if (!isOwner && !isStaff) {
            throw new BusinessException("You don't have access to this organization", 403);
        }
        return org;
    }

    /**
     * True when the caller is an active member of the class. Used to gate student
     * learning endpoints (e.g. module start/submit) that load a resource by id —
     * a centre student is allowed because they belong to the resource's class.
     */
    public boolean isActiveClassMember(String userId, String classId) {
        if (classId == null) {
            return false;
        }
        return membershipRepo.findByClassIdAndUserId(classId, userId)
                .map(m -> ClassMembershipJpaEntity.STATUS_ACTIVE.equals(m.getStatus()))
                .orElse(false);
    }

    /**
     * Block-unless-empty gate for an org OWNER who wants to delete their account
     * (ACCOUNT DELETION Phase 1, LOCKED policy). Returns {@code true} when the caller
     * owns NO organization, or every organization they own is EMPTY — defined as:
     * no classes AND no enrolled students AND no OTHER active staff. A non-empty
     * centre must be transferred or closed first (transfer flows are not built);
     * the caller maps {@code false} → 409 CENTRE_NOT_EMPTY.
     *
     * <p>Deliberately biased toward NOT-empty on any ambiguity: a false "empty"
     * would cascade-delete a live centre's classes and roll-up students (the org
     * owner FK is ON DELETE CASCADE), whereas a false "not empty" merely blocks the
     * owner from self-deleting, which is recoverable. This is evaluated at
     * delete-REQUEST time AND RE-CHECKED at purge time — an org acquired during the
     * 14-day grace window must abort the purge, never cascade.
     */
    public boolean isOwnedCentreEmpty(String userId) {
        for (OrganizationJpaEntity org : orgRepo.findByOwnerUserId(userId)) {
            String orgId = org.getId();
            if (orgClassRepo.countByOrganizationId(orgId) > 0) {
                return false; // has classes
            }
            if (userRepo.countByCentreId(orgId) > 0) {
                return false; // has enrolled students rolling up to this centre
            }
            boolean hasOtherStaff = staffRepo
                    .findByOrgIdAndStatus(orgId, OrgStaffJpaEntity.STATUS_ACTIVE)
                    .stream()
                    .anyMatch(s -> !userId.equals(s.getUserId()));
            if (hasOtherStaff) {
                return false; // another teacher/admin still relies on this centre
            }
        }
        return true;
    }
}
