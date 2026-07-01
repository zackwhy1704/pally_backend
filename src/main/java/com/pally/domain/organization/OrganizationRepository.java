package com.pally.domain.organization;

import java.util.Optional;

/**
 * Domain port for organization lookups needed outside the centre CRUD services.
 * Implemented by an infrastructure adapter; never import JPA here.
 */
public interface OrganizationRepository {

    /**
     * Returns the owner (principal adult) userId for the given org, or empty if
     * the org does not exist. Used to own hidden org-level assets such as the
     * (orgId, subject) marking-corpus avatar.
     */
    Optional<String> findOwnerUserIdById(String orgId);
}
