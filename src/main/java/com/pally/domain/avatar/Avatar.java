package com.pally.domain.avatar;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;

/**
 * Domain entity for an AI tutor avatar.
 * No Spring/JPA annotations — pure domain model.
 */
public final class Avatar {

    public enum PedagogyMode { SOCRATIC }

    public enum BrainState { READY, PENDING_RECOMPILE, COMPILING }

    private final String id;
    private String name;
    private final String userId;
    private final Subject subject;
    private final CharacterType characterType;
    private int wikiPageCount;
    private final Instant createdAt;
    private String gradeLevel;
    private String curriculumType;
    private PedagogyMode pedagogyMode;
    private TeachingMode teachingMode;
    private java.time.LocalDate testDate;
    private BrainState brainState;
    // true = user can chat/quiz with this avatar; false = locked (over free-tier slot cap)
    private boolean isActive;
    // Optional teacher-specified method preferences (max 500 chars), injected into Block 2
    private String teacherPreferences;

    private Avatar(
            String id,
            String userId,
            String name,
            Subject subject,
            CharacterType characterType,
            int wikiPageCount,
            Instant createdAt,
            String gradeLevel,
            String curriculumType,
            PedagogyMode pedagogyMode,
            TeachingMode teachingMode,
            java.time.LocalDate testDate,
            BrainState brainState,
            boolean isActive,
            String teacherPreferences
    ) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.subject = subject;
        this.characterType = characterType;
        this.wikiPageCount = wikiPageCount;
        this.createdAt = createdAt;
        this.gradeLevel = gradeLevel;
        this.curriculumType = curriculumType;
        this.pedagogyMode = pedagogyMode != null ? pedagogyMode : PedagogyMode.SOCRATIC;
        this.teachingMode = teachingMode != null ? teachingMode : TeachingMode.TEACHING;
        this.testDate = testDate;
        this.brainState = brainState != null ? brainState : BrainState.READY;
        this.isActive = isActive;
        this.teacherPreferences = teacherPreferences;
    }

    /**
     * Factory method — enforces all invariants at creation time.
     */
    public static Avatar create(String userId, String name, Subject subject, CharacterType characterType) {
        return create(userId, name, subject, characterType, null, null);
    }

    public static Avatar create(
            String userId, String name, Subject subject, CharacterType characterType,
            String gradeLevel, String curriculumType
    ) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId cannot be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Avatar name cannot be blank");
        if (subject == null) throw new IllegalArgumentException("Subject is required");
        if (characterType == null) throw new IllegalArgumentException("CharacterType is required");
        return new Avatar(IdGenerator.newId(), userId, name, subject, characterType, 0, Instant.now(),
                gradeLevel, curriculumType, PedagogyMode.SOCRATIC, TeachingMode.TEACHING, null, BrainState.READY, true, null);
    }

    /**
     * Reconstitution factory — used by persistence adapters to rebuild from storage.
     */
    public static Avatar reconstitute(
            String id,
            String userId,
            String name,
            Subject subject,
            CharacterType characterType,
            int wikiPageCount,
            Instant createdAt
    ) {
        return reconstitute(id, userId, name, subject, characterType, wikiPageCount, createdAt,
                null, null, PedagogyMode.SOCRATIC, TeachingMode.TEACHING, null, BrainState.READY, true, null);
    }

    public static Avatar reconstitute(
            String id,
            String userId,
            String name,
            Subject subject,
            CharacterType characterType,
            int wikiPageCount,
            Instant createdAt,
            String gradeLevel,
            String curriculumType,
            PedagogyMode pedagogyMode,
            TeachingMode teachingMode,
            java.time.LocalDate testDate
    ) {
        return reconstitute(id, userId, name, subject, characterType, wikiPageCount, createdAt,
                gradeLevel, curriculumType, pedagogyMode, teachingMode, testDate, BrainState.READY, true, null);
    }

    public static Avatar reconstitute(
            String id,
            String userId,
            String name,
            Subject subject,
            CharacterType characterType,
            int wikiPageCount,
            Instant createdAt,
            String gradeLevel,
            String curriculumType,
            PedagogyMode pedagogyMode,
            TeachingMode teachingMode,
            java.time.LocalDate testDate,
            BrainState brainState
    ) {
        return reconstitute(id, userId, name, subject, characterType, wikiPageCount, createdAt,
                gradeLevel, curriculumType, pedagogyMode, teachingMode, testDate, brainState, true, null);
    }

    public static Avatar reconstitute(
            String id,
            String userId,
            String name,
            Subject subject,
            CharacterType characterType,
            int wikiPageCount,
            Instant createdAt,
            String gradeLevel,
            String curriculumType,
            PedagogyMode pedagogyMode,
            TeachingMode teachingMode,
            java.time.LocalDate testDate,
            BrainState brainState,
            boolean isActive
    ) {
        return reconstitute(id, userId, name, subject, characterType, wikiPageCount, createdAt,
                gradeLevel, curriculumType, pedagogyMode, teachingMode, testDate, brainState, isActive, null);
    }

    public static Avatar reconstitute(
            String id,
            String userId,
            String name,
            Subject subject,
            CharacterType characterType,
            int wikiPageCount,
            Instant createdAt,
            String gradeLevel,
            String curriculumType,
            PedagogyMode pedagogyMode,
            TeachingMode teachingMode,
            java.time.LocalDate testDate,
            BrainState brainState,
            boolean isActive,
            String teacherPreferences
    ) {
        return new Avatar(id, userId, name, subject, characterType, wikiPageCount, createdAt,
                gradeLevel, curriculumType, pedagogyMode, teachingMode, testDate, brainState, isActive, teacherPreferences);
    }

    // Domain behaviour
    public void rename(String newName) {
        if (newName == null || newName.isBlank()) throw new IllegalArgumentException("Name cannot be blank");
        this.name = newName;
    }

    public void incrementWikiPageCount() {
        this.wikiPageCount++;
    }

    public void setWikiPageCount(int count) {
        if (count < 0) throw new IllegalArgumentException("wiki page count cannot be negative");
        this.wikiPageCount = count;
    }

    public void setPedagogyMode(PedagogyMode mode) {
        if (mode == null) throw new IllegalArgumentException("PedagogyMode cannot be null");
        this.pedagogyMode = mode;
    }

    public void setTeachingMode(TeachingMode mode) {
        if (mode == null) throw new IllegalArgumentException("TeachingMode cannot be null");
        this.teachingMode = mode;
    }

    public void setGradeLevel(String gradeLevel) {
        this.gradeLevel = gradeLevel;
    }

    public void setCurriculumType(String curriculumType) {
        this.curriculumType = curriculumType;
    }

    public void setTestDate(java.time.LocalDate testDate) {
        this.testDate = testDate;
    }

    // Accessors
    public String getId()                       { return id; }
    public String getUserId()                   { return userId; }
    public String getName()                     { return name; }
    public Subject getSubject()                 { return subject; }
    public CharacterType getCharacterType()     { return characterType; }
    public int getWikiPageCount()               { return wikiPageCount; }
    public Instant getCreatedAt()               { return createdAt; }
    public String getGradeLevel()               { return gradeLevel; }
    public String getCurriculumType()           { return curriculumType; }
    public PedagogyMode getPedagogyMode()       { return pedagogyMode; }
    public TeachingMode getTeachingMode()       { return teachingMode; }
    public java.time.LocalDate getTestDate()    { return testDate; }
    public BrainState getBrainState()           { return brainState != null ? brainState : BrainState.READY; }
    public void setBrainState(BrainState state) { this.brainState = state != null ? state : BrainState.READY; }
    public boolean isActive()                   { return isActive; }
    public void setActive(boolean active)       { this.isActive = active; }
    public String getTeacherPreferences()       { return teacherPreferences; }
    public void setTeacherPreferences(String prefs) { this.teacherPreferences = prefs; }
}
