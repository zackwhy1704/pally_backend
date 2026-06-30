package com.pally.domain.homework;

/**
 * Port for the AI first-pass feedback draft. The implementation calls the LLM;
 * the domain service supplies the grading context (the class brain) and the
 * student's work. The output is ALWAYS a draft — the service never releases it.
 */
public interface HomeworkFeedbackGenerator {

    /**
     * Generate a JSON feedback draft grounded in the class's own materials.
     *
     * @param brainContext     concatenated class brain (rubric/notes); may be empty
     * @param studentWorkText  OCR/extracted text of the work; may be empty/unreadable
     * @param title            the homework title (for context)
     * @param subject          the subject (may be null)
     * @return a JSON string of shape
     *         {@code {"suggestedGrade":..,"criteria":[{"criterion":..,"comment":..}],"feedback":..}}.
     *         Implementations should throw on hard failure so the teacher can fall
     *         back to manual marking.
     */
    String generateDraftJson(String brainContext, String studentWorkText, String title, String subject);
}
