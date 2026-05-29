package com.pally.infrastructure.persistence.progress;

import com.pally.domain.progress.UserStats;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class UserJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(unique = true)
    private String email;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "parent_pin_hash", length = 100)
    private String parentPinHash;

    @Column(nullable = false)
    private int stars;

    @Column(nullable = false)
    private int xp;

    @Column(nullable = false)
    private int level;

    @Column(name = "streak_days", nullable = false)
    private int streakDays;

    @Column(name = "last_active_date")
    private LocalDate lastActiveDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "setup_complete", nullable = false)
    private boolean setupComplete;

    @Column(name = "child_name", length = 100)
    private String childName;

    @Column(name = "year_level")
    private Integer yearLevel;

    @Column(name = "curriculum", length = 30)
    private String curriculum;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "biometric_failed_attempts", nullable = false)
    private int biometricFailedAttempts;

    @Column(name = "biometric_locked_until")
    private Instant biometricLockedUntil;

    @Column(name = "screen_time_enabled", nullable = false)
    private boolean screenTimeEnabled;

    @Column(name = "screen_time_minutes", nullable = false)
    private int screenTimeMinutes = 60;

    /// Family account model. SOLO is the legacy default.
    @Column(name = "account_type", nullable = false, length = 10)
    private String accountType = "SOLO";

    /// FK to the parent account when this user is a CHILD.
    @Column(name = "parent_id", length = 36)
    private String parentId;

    /// Short, unique, time-limited claim code a child generates so a
    /// parent can attach them to the family. Cleared on claim / expiry.
    @Column(name = "link_code", length = 12, unique = true)
    private String linkCode;

    @Column(name = "link_code_expires_at")
    private Instant linkCodeExpiresAt;

    public static UserJpaEntity newUser(String id) {
        UserJpaEntity e = new UserJpaEntity();
        e.id = id;
        e.displayName = "Player";
        e.stars = 0;
        e.xp = 0;
        e.level = 1;
        e.streakDays = 0;
        e.createdAt = Instant.now();
        return e;
    }

    public static UserJpaEntity fromDomain(UserStats stats) {
        UserJpaEntity e = new UserJpaEntity();
        e.id = stats.id();
        e.displayName = stats.displayName();
        e.xp = stats.xp();
        e.level = stats.level();
        e.streakDays = stats.streakDays();
        e.stars = stats.stars();
        e.createdAt = Instant.now();
        return e;
    }

    public UserStats toDomain() {
        return new UserStats(id, displayName != null ? displayName : "Player", xp, level, streakDays, stars);
    }
}
