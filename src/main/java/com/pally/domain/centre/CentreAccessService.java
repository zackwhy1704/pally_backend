package com.pally.domain.centre;

import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Shared ownership guard for all centre-scoped admin operations.
 * Extracted from {@link com.pally.api.centre.CentreController} so that
 * every analytics controller can enforce the same 403/404 invariants without
 * duplicating the logic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentreAccessService {

    private final OrganizationJpaRepository orgRepo;

    /**
     * Returns the org entity after verifying {@code userId == owner}.
     * Throws a 404 {@link BusinessException} if the org doesn't exist,
     * and a 403 if the caller is not the owner.
     */
    public OrganizationJpaEntity ensureOwner(String userId, String orgId) {
        OrganizationJpaEntity org = orgRepo.findById(orgId)
                .orElseThrow(() -> new BusinessException("Organization not found", 404));
        if (!org.getOwnerUserId().equals(userId)) {
            throw new BusinessException("You don't own this organization", 403);
        }
        return org;
    }
}
