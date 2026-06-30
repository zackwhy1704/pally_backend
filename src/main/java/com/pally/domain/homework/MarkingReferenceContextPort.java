package com.pally.domain.homework;

/**
 * Port the homework feedback generator uses to ground an AI draft in the
 * teacher's OWN marking standard (uploaded marked papers / rubrics / guidelines).
 *
 * <p>Declared in the homework context and implemented on the marking side so
 * homework depends on an interface, not on the marking domain — no cross-context
 * coupling, no bean cycle. Returns "" (never null) when the class has no marking
 * references, so grounding is purely additive.
 */
public interface MarkingReferenceContextPort {

    /**
     * Concatenated marking-reference grounding text for a class, capped at
     * {@code maxChars}.
     *
     * @return grounding text, or "" if the class has no marking references. Never null.
     */
    String contextForClass(String classId, int maxChars);
}
