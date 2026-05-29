package com.pally.domain.quiz;

import java.util.List;
import java.util.Optional;

public interface FlashcardRepository {
    List<FlashCard> findByAvatarId(String avatarId);
    List<FlashCard> findDueByAvatarId(String avatarId);
    Optional<FlashCard> findById(String id);
    FlashCard save(FlashCard card);
    List<FlashCard> saveAll(List<FlashCard> cards);

    /// Total card count for a single avatar — used by progress aggregation.
    int countByAvatarId(String avatarId);

    /// Due-card count without loading the rows. Replaces
    /// `findDueByAvatarId(id).size()` in hot paths (progress dashboard,
    /// home banner) so we don't materialise 100s of rows just to count.
    int countDueByAvatarId(String avatarId);

    /// Delete every card for a given (avatarId, sourceSlug) pair so the wiki
    /// compiler can re-generate cleanly when a page is recompiled.
    void deleteByAvatarIdAndSourceSlug(String avatarId, String sourceSlug);
}
