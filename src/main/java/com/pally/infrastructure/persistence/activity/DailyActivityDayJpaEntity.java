package com.pally.infrastructure.persistence.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/// One row per (user, day) the user did anything that credits XP. Powers
/// the 7-dot week strip on the streak card.
@Entity
@Table(name = "daily_activity_days")
@IdClass(DailyActivityDayJpaEntity.PK.class)
@Getter
@Setter
@NoArgsConstructor
public class DailyActivityDayJpaEntity {

    @Id
    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Id
    @Column(name = "day", nullable = false)
    private LocalDate day;

    public static class PK implements Serializable {
        private String userId;
        private LocalDate day;

        public PK() {}

        public PK(String userId, LocalDate day) {
            this.userId = userId;
            this.day = day;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(userId, pk.userId)
                    && Objects.equals(day, pk.day);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, day);
        }
    }
}
