package com.pally.infrastructure.persistence.mochi;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "user_mochi")
@Getter
@Setter
@NoArgsConstructor
public class UserMochiJpaEntity {

    @EmbeddedId
    private Id id;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "acquired_via", length = 12)
    private String acquiredVia;

    public UserMochiJpaEntity(String userId, String mochiId, String acquiredVia) {
        this.id = new Id(userId, mochiId);
        this.acquiredAt = Instant.now();
        this.acquiredVia = acquiredVia;
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Id implements Serializable {
        @Column(name = "user_id", nullable = false, length = 36)
        private String userId;
        @Column(name = "mochi_id", nullable = false, length = 40)
        private String mochiId;

        public Id(String userId, String mochiId) {
            this.userId = userId;
            this.mochiId = mochiId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id other)) return false;
            return Objects.equals(userId, other.userId)
                    && Objects.equals(mochiId, other.mochiId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, mochiId);
        }
    }
}
