package com.pally.infrastructure.persistence.avatar;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.avatar.TeachingMode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "avatars")
@Getter
@Setter
@NoArgsConstructor
public class AvatarJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Subject subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "character_type", nullable = false, length = 30)
    private CharacterType characterType;

    @Column(name = "wiki_page_count", nullable = false)
    private int wikiPageCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "grade_level", length = 10)
    private String gradeLevel;

    @Column(name = "curriculum_type", length = 30)
    private String curriculumType;

    @Enumerated(EnumType.STRING)
    @Column(name = "pedagogy_mode", nullable = false, length = 20)
    private Avatar.PedagogyMode pedagogyMode = Avatar.PedagogyMode.SOCRATIC;

    @Enumerated(EnumType.STRING)
    @Column(name = "teaching_mode", nullable = false, length = 20)
    private TeachingMode teachingMode = TeachingMode.TEACHING;

    @Column(name = "test_date")
    private LocalDate testDate;

    /// Optional curriculum attached to this avatar — drives the syllabus
    /// coverage journey UX. Persisted at the infra layer only; the domain
    /// Avatar stays independent of the curriculum module.
    @Column(name = "curriculum_id", length = 36)
    private String curriculumId;

    @Column(name = "brain_state", nullable = false, length = 20)
    private String brainState = "READY";

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "teacher_preferences", columnDefinition = "TEXT")
    private String teacherPreferences;

    /// Set to true when the avatar was created by centre enrolment (not by the student).
    @Column(name = "centre_avatar", nullable = false)
    private boolean centreAvatar;

    /// When true the student cannot chat with this avatar — set by centre lapse/leave.
    @Column(name = "avatar_locked", nullable = false)
    private boolean avatarLocked;

    // ── Centre class linkage + branding + cosmetics (V59) ────────────────────
    @Column(name = "class_id", length = 36)
    private String classId;

    @Column(name = "corpus_avatar_id", length = 36)
    private String corpusAvatarId;

    @Column(name = "centre_brand_name", length = 120)
    private String centreBrandName;

    @Column(name = "centre_accent_color", length = 9)
    private String centreAccentColor;

    @Column(name = "cosmetic_eyewear", length = 40)
    private String cosmeticEyewear;

    @Column(name = "cosmetic_clothes", length = 40)
    private String cosmeticClothes;

    @Column(name = "cosmetic_shoes", length = 40)
    private String cosmeticShoes;

    public static AvatarJpaEntity fromDomain(Avatar avatar) {
        AvatarJpaEntity entity = new AvatarJpaEntity();
        entity.id = avatar.getId();
        entity.userId = avatar.getUserId();
        entity.name = avatar.getName();
        entity.subject = avatar.getSubject();
        entity.characterType = avatar.getCharacterType();
        entity.wikiPageCount = avatar.getWikiPageCount();
        entity.createdAt = avatar.getCreatedAt();
        entity.gradeLevel = avatar.getGradeLevel();
        entity.curriculumType = avatar.getCurriculumType();
        entity.pedagogyMode = avatar.getPedagogyMode();
        entity.teachingMode = avatar.getTeachingMode();
        entity.testDate = avatar.getTestDate();
        entity.brainState = avatar.getBrainState().name();
        entity.isActive = avatar.isActive();
        entity.teacherPreferences = avatar.getTeacherPreferences();
        // Centre flags — must round-trip or provisioned avatars persist as
        // non-centre (breaking centre-mode UI, the closed-book gate, and locks).
        entity.centreAvatar = avatar.isCentreAvatar();
        entity.avatarLocked = avatar.isAvatarLocked();
        entity.classId = avatar.getClassId();
        entity.corpusAvatarId = avatar.getCorpusAvatarId();
        entity.centreBrandName = avatar.getCentreBrandName();
        entity.centreAccentColor = avatar.getCentreAccentColor();
        entity.cosmeticEyewear = avatar.getCosmeticEyewear();
        entity.cosmeticClothes = avatar.getCosmeticClothes();
        entity.cosmeticShoes = avatar.getCosmeticShoes();
        return entity;
    }

    public Avatar toDomain() {
        Avatar.BrainState bs;
        try {
            bs = brainState != null ? Avatar.BrainState.valueOf(brainState) : Avatar.BrainState.READY;
        } catch (IllegalArgumentException e) {
            bs = Avatar.BrainState.READY;
        }
        Avatar a = Avatar.reconstitute(id, userId, name, subject, characterType, wikiPageCount, createdAt,
                gradeLevel, curriculumType, pedagogyMode, teachingMode, testDate, bs, isActive, teacherPreferences,
                centreAvatar, avatarLocked);
        a.setClassId(classId);
        a.setCorpusAvatarId(corpusAvatarId);
        a.setCentreBrandName(centreBrandName);
        a.setCentreAccentColor(centreAccentColor);
        a.setCosmeticEyewear(cosmeticEyewear);
        a.setCosmeticClothes(cosmeticClothes);
        a.setCosmeticShoes(cosmeticShoes);
        return a;
    }
}
