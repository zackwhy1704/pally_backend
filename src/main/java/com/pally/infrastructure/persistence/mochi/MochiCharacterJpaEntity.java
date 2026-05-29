package com.pally.infrastructure.persistence.mochi;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "mochi_characters")
@Getter
@Setter
@NoArgsConstructor
public class MochiCharacterJpaEntity {

    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "theme_id", length = 36)
    private String themeId;

    /// COMMON | RARE | SECRET — string for forward-compat with new tiers
    /// (e.g. EPIC for a future event) without a migration.
    @Column(nullable = false, length = 10)
    private String rarity;

    /// DEFAULT | STAR_SHOP | MYSTERY_BOX | LEVEL | SEASONAL.
    @Column(nullable = false, length = 12)
    private String acquisition;

    @Column(name = "star_cost")
    private Integer starCost;

    @Column(name = "active_from")
    private Instant activeFrom;

    @Column(name = "active_until")
    private Instant activeUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /// Returns true iff this character is acquirable *right now*. Active
    /// windows are null for the always-on Core theme — null on either
    /// bound means "no bound."
    public boolean isActiveAt(Instant when) {
        if (activeFrom != null && when.isBefore(activeFrom)) return false;
        if (activeUntil != null && !when.isBefore(activeUntil)) return false;
        return true;
    }
}
