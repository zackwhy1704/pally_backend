package com.pally.infrastructure.persistence.boss;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "boss_instance")
@Getter
@Setter
@NoArgsConstructor
public class BossInstanceJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(name = "topic_slug", nullable = false, length = 200)
    private String topicSlug;

    @Column(name = "question_pool_json", nullable = false, columnDefinition = "text")
    private String questionPoolJson;

    @Column(name = "current_index", nullable = false)
    private int currentIndex;

    @Column(name = "hp_remaining", nullable = false)
    private int hpRemaining;

    @Column(name = "hp_max", nullable = false)
    private int hpMax;

    @Column(nullable = false)
    private boolean defeated;

    @Column(name = "reward_unlocked", nullable = false)
    private boolean rewardUnlocked;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "defeated_at")
    private Instant defeatedAt;
}
