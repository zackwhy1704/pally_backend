package com.pally.domain.centre;

import java.util.Optional;

/**
 * Domain port for org-class lookup operations needed by centre services.
 * Implemented by an infrastructure adapter; never import JPA here.
 */
public interface OrgClassRepository {

    /**
     * Returns the organization-id that owns the given class,
     * or empty if the class does not exist.
     */
    Optional<String> findOrganizationIdByClassId(String classId);

    /**
     * Returns the corpus avatar id for the given class, or empty if the class
     * does not exist or has no corpus avatar assigned yet.
     */
    Optional<String> findCorpusAvatarIdByClassId(String classId);
}
