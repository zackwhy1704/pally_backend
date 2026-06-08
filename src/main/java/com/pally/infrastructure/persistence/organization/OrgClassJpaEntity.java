package com.pally.infrastructure.persistence.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A class (subject×level) within a centre. Owns a join code, a shared corpus
 * (wiki_pages.class_id), a branded Mochi (character + brand + accent + cosmetic
 * accessory slots), and its own analytics. See V59 migration.
 */
@Entity
@Table(name = "org_class")
@Getter
@Setter
@NoArgsConstructor
public class OrgClassJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "organization_id", nullable = false, length = 36)
    private String organizationId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 80)
    private String subject;

    @Column(length = 50)
    private String level;

    @Column(name = "join_code", nullable = false, length = 12)
    private String joinCode;

    @Column(name = "corpus_avatar_id", length = 36)
    private String corpusAvatarId;

    @Column(name = "character_type", nullable = false, length = 30)
    private String characterType = "MOCHI";

    @Column(name = "brand_name", length = 120)
    private String brandName;

    @Column(name = "accent_color", length = 9)
    private String accentColor;

    @Column(name = "exam_date")
    private LocalDate examDate;

    // Cosmetic accessory slots — inert until layered art exists (Part 3).
    @Column(name = "cosmetic_eyewear", length = 40)
    private String cosmeticEyewear;

    @Column(name = "cosmetic_clothes", length = 40)
    private String cosmeticClothes;

    @Column(name = "cosmetic_shoes", length = 40)
    private String cosmeticShoes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
