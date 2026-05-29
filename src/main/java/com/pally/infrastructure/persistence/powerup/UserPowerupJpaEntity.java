package com.pally.infrastructure.persistence.powerup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "user_powerups")
@Getter
@Setter
@NoArgsConstructor
public class UserPowerupJpaEntity {

    @EmbeddedId
    private Id id;

    @Column(name = "count", nullable = false)
    private int count;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserPowerupJpaEntity(String userId, String type, int count) {
        this.id = new Id(userId, type);
        this.count = count;
        this.updatedAt = Instant.now();
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Id implements Serializable {
        @Column(name = "user_id", nullable = false, length = 36)
        private String userId;
        @Column(name = "type", nullable = false, length = 40)
        private String type;

        public Id(String userId, String type) {
            this.userId = userId;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id other)) return false;
            return Objects.equals(userId, other.userId)
                    && Objects.equals(type, other.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, type);
        }
    }
}
