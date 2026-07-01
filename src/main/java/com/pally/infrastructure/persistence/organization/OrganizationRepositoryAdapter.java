package com.pally.infrastructure.persistence.organization;

import com.pally.domain.organization.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * JPA adapter for the {@link OrganizationRepository} domain port. Resolves an
 * org's owner without leaking JPA into the domain.
 */
@Component
@RequiredArgsConstructor
public class OrganizationRepositoryAdapter implements OrganizationRepository {

    private final OrganizationJpaRepository jpa;

    @Override
    public Optional<String> findOwnerUserIdById(String orgId) {
        return jpa.findById(orgId).map(OrganizationJpaEntity::getOwnerUserId);
    }
}
