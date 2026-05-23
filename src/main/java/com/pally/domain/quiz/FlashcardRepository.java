package com.pally.domain.quiz;

import java.util.List;
import java.util.Optional;

public interface FlashcardRepository {
    List<FlashCard> findByAvatarId(String avatarId);
    List<FlashCard> findDueByAvatarId(String avatarId);
    Optional<FlashCard> findById(String id);
    FlashCard save(FlashCard card);
    List<FlashCard> saveAll(List<FlashCard> cards);
}
