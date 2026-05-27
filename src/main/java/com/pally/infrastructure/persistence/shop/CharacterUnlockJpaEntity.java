package com.pally.infrastructure.persistence.shop;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "character_unlocks")
@Getter @Setter @NoArgsConstructor
public class CharacterUnlockJpaEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "user_id", nullable = false) private String userId;
    @Column(nullable = false, length = 30) private String character;
    @Column(name = "unlocked_at", nullable = false) private Instant unlockedAt;
}
