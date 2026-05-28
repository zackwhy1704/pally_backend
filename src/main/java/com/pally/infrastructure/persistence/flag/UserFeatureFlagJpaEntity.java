package com.pally.infrastructure.persistence.flag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Per-user opt-in flag for pilot features (e.g. study groups). Absence of a
 * row means the flag is off for that user.
 */
@Entity
@Table(name = "user_feature_flags")
@IdClass(UserFeatureFlagJpaEntity.PK.class)
@Getter
@Setter
@NoArgsConstructor
public class UserFeatureFlagJpaEntity {

    @Id
    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Id
    @Column(name = "flag_name", length = 50, nullable = false)
    private String flagName;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public static class PK implements Serializable {
        private String userId;
        private String flagName;

        public PK() {}

        public PK(String userId, String flagName) {
            this.userId = userId;
            this.flagName = flagName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(userId, pk.userId)
                    && Objects.equals(flagName, pk.flagName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, flagName);
        }
    }
}
