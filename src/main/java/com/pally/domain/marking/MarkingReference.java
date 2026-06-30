package com.pally.domain.marking;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;
import java.util.List;

/**
 * Domain entity for one marking reference — a teacher-uploaded marked paper,
 * rubric, or guideline that grounds the AI homework-feedback draft in THAT
 * teacher's own marking standard. Pure domain model — no Spring/JPA.
 *
 * <p>A reference is immutable content once created (you re-upload rather than
 * edit); only {@code updatedAt} is mutable, kept for a uniform persistence
 * shape. The extracted text is the grounding payload — {@code files} stay the
 * viewable originals.
 */
public final class MarkingReference {

    private final String id;
    private final String classId;
    private final MarkingReferenceKind kind;
    private final String title;
    private final String note;
    private final List<MarkingReferenceFile> files;
    private final String extractedText;
    private final Instant createdAt;
    private Instant updatedAt;

    private MarkingReference(
            String id, String classId, MarkingReferenceKind kind, String title, String note,
            List<MarkingReferenceFile> files, String extractedText,
            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.classId = classId;
        this.kind = kind;
        this.title = title;
        this.note = note;
        this.files = files;
        this.extractedText = extractedText;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A freshly uploaded marking reference. */
    public static MarkingReference create(String classId, MarkingReferenceKind kind, String title,
                                          String note, List<MarkingReferenceFile> files,
                                          String extractedText) {
        Instant now = Instant.now();
        return new MarkingReference(
                IdGenerator.newId(), classId, kind, title, note, files, extractedText, now, now);
    }

    /** Rehydrate from persistence. */
    public static MarkingReference reconstitute(
            String id, String classId, MarkingReferenceKind kind, String title, String note,
            List<MarkingReferenceFile> files, String extractedText,
            Instant createdAt, Instant updatedAt) {
        return new MarkingReference(
                id, classId, kind, title, note, files, extractedText, createdAt, updatedAt);
    }

    /** How much grounding text we indexed from this reference (for the UI hint). */
    public int getExtractedChars() {
        return extractedText == null ? 0 : extractedText.length();
    }

    public String getId() { return id; }
    public String getClassId() { return classId; }
    public MarkingReferenceKind getKind() { return kind; }
    public String getTitle() { return title; }
    public String getNote() { return note; }
    public List<MarkingReferenceFile> getFiles() { return files; }
    public String getExtractedText() { return extractedText; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
