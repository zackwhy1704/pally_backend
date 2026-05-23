package com.pally.domain.knowledge;

import java.util.List;
import java.util.Optional;

/**
 * Port for wiki page persistence.
 */
public interface WikiRepository {

    WikiPage save(WikiPage wikiPage);

    List<WikiPage> saveAll(List<WikiPage> pages);

    Optional<WikiPage> findByAvatarIdAndSlug(String avatarId, String slug);

    List<WikiPage> findByAvatarId(String avatarId);

    int countByAvatarId(String avatarId);

    void deleteByAvatarId(String avatarId);
}
