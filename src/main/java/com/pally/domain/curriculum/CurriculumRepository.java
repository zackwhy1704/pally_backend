package com.pally.domain.curriculum;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for {@link Curriculum} persistence.
 * Implemented by infrastructure adapters; never import JPA here.
 */
public interface CurriculumRepository {

    List<Curriculum> findVisibleToUser(String userId, String subject);

    Optional<Curriculum> findById(String id);

    Curriculum save(Curriculum curriculum);
}
