package com.pally.domain.chat;

import java.util.List;
import java.util.Optional;

public interface HintTreeRepository {
    void save(SocraticHintTree tree);
    Optional<SocraticHintTree> findByAvatarIdAndSlug(String avatarId, String wikiSlug);
    List<SocraticHintTree> findByAvatarId(String avatarId);
    void deleteByAvatarIdAndSlug(String avatarId, String wikiSlug);
}
