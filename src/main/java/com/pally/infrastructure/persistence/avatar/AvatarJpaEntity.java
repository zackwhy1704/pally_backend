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
        return entity;
    }

    public Avatar toDomain() {
        return Avatar.reconstitute(id, userId, name, subject, characterType, wikiPageCount, createdAt,
                gradeLevel, curriculumType, pedagogyMode, teachingMode, testDate);
    }
}
