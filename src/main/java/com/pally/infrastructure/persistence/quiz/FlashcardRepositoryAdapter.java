package com.pally.infrastructure.persistence.quiz;

import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class FlashcardRepositoryAdapter implements FlashcardRepository {

    private final FlashcardJpaRepository jpa;

    @Override
    public List<FlashCard> findByAvatarId(String avatarId) {
        return jpa.findByAvatarId(avatarId).stream()
                .map(FlashcardJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<FlashCard> findDueByAvatarId(String avatarId) {
        return jpa.findDueByAvatarId(avatarId, Instant.now()).stream()
                .map(FlashcardJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<FlashCard> findById(String id) {
        return jpa.findById(id).map(FlashcardJpaEntity::toDomain);
    }

    @Override
    public FlashCard save(FlashCard card) {
        return jpa.save(FlashcardJpaEntity.fromDomain(card)).toDomain();
    }

    @Override
    public List<FlashCard> saveAll(List<FlashCard> cards) {
        return jpa.saveAll(cards.stream().map(FlashcardJpaEntity::fromDomain).toList())
                .stream().map(FlashcardJpaEntity::toDomain).toList();
    }

    @Override
    public int countByAvatarId(String avatarId) {
        return jpa.countByAvatarId(avatarId);
    }

    @Override
    public int countDueByAvatarId(String avatarId) {
        return jpa.countDueByAvatarId(avatarId, Instant.now());
    }

    @Override
    public void deleteByAvatarIdAndSourceSlug(String avatarId, String sourceSlug) {
        if (sourceSlug == null) return;
        jpa.deleteByAvatarIdAndSourceSlug(avatarId, sourceSlug);
    }
}
