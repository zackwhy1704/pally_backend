package com.pally.domain.marking;

/**
 * The kind of marking reference a teacher uploads to train their marking
 * assistant. Each kind grounds the AI homework-feedback draft in a different
 * facet of the teacher's OWN standard:
 *
 * <ul>
 *   <li>{@link #MARKED_PAPER} — a past paper the teacher already marked (an
 *       exemplar across a grade band): the strongest signal of how this teacher
 *       awards marks and phrases comments.</li>
 *   <li>{@link #RUBRIC} — a mark scheme / rubric / grade descriptors.</li>
 *   <li>{@link #GUIDELINE} — free-form marking guidance or house style.</li>
 * </ul>
 */
public enum MarkingReferenceKind {
    MARKED_PAPER,
    RUBRIC,
    GUIDELINE
}
