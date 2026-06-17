package com.pally.domain.centre;

import com.pally.infrastructure.persistence.organization.OrgStaffJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
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
}
