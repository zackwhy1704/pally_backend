package com.pally.api.chat.dto;

import java.util.List;

/**
 * Per-question answer returned by the photo solver.
 *
 * @param visualType        NONE | CHART | GRAPH | DIAGRAM | GEOMETRY | TABLE —
 *                          set when the question requires reading a visual element.
 * @param calculatorVerified true when the final numeric answer was computed via the
 *                           deterministic calculator tool (not predicted by the model).
 */
public record QuestionAnswerDto(
        String questionId,
        String questionText,
        String answer,
        List<String> steps,
        String explanation,
        String visualType,
        boolean calculatorVerified
) {
    /** Backwards-compatible constructor for callers that pre-date visual-type fields. */
    public QuestionAnswerDto(String questionId, String questionText, String answer,
                              List<String> steps, String explanation) {
        this(questionId, questionText, answer, steps, explanation, "NONE", false);
    }
}
