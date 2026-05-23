package com.pally.domain.avatar;

import java.util.List;
import java.util.Optional;

/**
 * Port (output) — defines persistence operations for {@link Avatar}.
 * Implemented by infrastructure adapters; never import JPA here.
 */
public interface AvatarRepository {

    Avatar save(Avatar avatar);

    Optional<Avatar> findById(String id);

    List<Avatar> findByUserId(String userId);

    boolean existsByIdAndUserId(String id, String userId);

    void deleteById(String id);
}
