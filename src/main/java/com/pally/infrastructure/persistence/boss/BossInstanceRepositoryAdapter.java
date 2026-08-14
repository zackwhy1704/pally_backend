package com.pally.infrastructure.persistence.boss;

import com.pally.domain.boss.BossInstance;
import com.pally.domain.boss.BossInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class BossInstanceRepositoryAdapter implements BossInstanceRepository {

    private final BossInstanceJpaRepository jpa;

    @Override
    @Transactional(readOnly = true)
    public Optional<BossInstance> findActiveByAvatarId(String avatarId) {
        return jpa.findFirstByAvatarIdAndDefeatedFalse(avatarId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BossInstance> findById(String id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional
    public BossInstance save(BossInstance boss) {
        BossInstanceJpaEntity e = new BossInstanceJpaEntity();
        e.setId(boss.id());
        e.setUserId(boss.userId());
        e.setAvatarId(boss.avatarId());
        e.setTopicSlug(boss.topicSlug());
        e.setQuestionPoolJson(boss.questionPoolJson());
        e.setCurrentIndex(boss.currentIndex());
        e.setHpRemaining(boss.hpRemaining());
        e.setHpMax(boss.hpMax());
        e.setDefeated(boss.defeated());
        e.setRewardUnlocked(boss.rewardUnlocked());
        e.setCreatedAt(boss.createdAt());
        e.setDefeatedAt(boss.defeatedAt());
        jpa.save(e);
        return boss;
    }

    private BossInstance toDomain(BossInstanceJpaEntity e) {
        return new BossInstance(e.getId(), e.getUserId(), e.getAvatarId(), e.getTopicSlug(),
                e.getQuestionPoolJson(), e.getCurrentIndex(), e.getHpRemaining(), e.getHpMax(),
                e.isDefeated(), e.isRewardUnlocked(), e.getCreatedAt(), e.getDefeatedAt());
    }
}
