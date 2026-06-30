package com.pally.infrastructure.ai;

import com.pally.domain.homework.HomeworkFeedbackGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI first-pass homework feedback, grounded in the class's own materials and
 * framed in Singapore exam / mark-scheme vocabulary. Uses Haiku (centre AI cost
 * policy). The output is a teacher-only DRAFT — the domain service never
 * releases it without the teacher's edit + sign-off.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClaudeHomeworkFeedbackGenerator implements HomeworkFeedbackGenerator {

    private static final int MAX_TOKENS = 1200;

    private final ModelRouter modelRouter;
    private final ClaudeApiClient claudeApiClient;

    @Override
    public String generateDraftJson(String brainContext, String studentWorkText,
                                    String title, String subject) {
        String prompt = buildPrompt(brainContext, studentWorkText, title, subject);
        String raw = claudeApiClient.complete(
                modelRouter.getHaikuModel(), MAX_TOKENS, prompt, "homework-feedback");
        return stripFences(raw);
    }

    private String buildPrompt(String brain, String work, String title, String subject) {
        String subjectLine = (subject == null || subject.isBlank()) ? "" : " (" + subject + ")";
        return """
                You are an experienced Singapore tuition teacher marking a student's homework.
                Mark the student's work AGAINST THE CLASS'S OWN TEACHING MATERIALS below — not a
                generic syllabus. Use Singapore exam / mark-scheme vocabulary where it fits
                (e.g. method marks, model method, "show your working", Paper 1/2 phrasing).
                Be specific, kind, and concrete. This is a FIRST-PASS DRAFT for the teacher to
                edit before it reaches the student — do not address the student directly as final.

                Return STRICT JSON ONLY (no markdown, no prose, no code fences) of EXACTLY this shape:
                {
                  "suggestedGrade": "<short grade like \\"B+\\" or \\"17/25\\", or \\"\\" if you cannot tell>",
                  "criteria": [
                    {"criterion": "<what you assessed>", "comment": "<specific, brief feedback>"}
                  ],
                  "feedback": "<one concise encouraging paragraph the teacher can lightly edit>"
                }

                If the STUDENT WORK is empty or unreadable, set "suggestedGrade" to "" and say so
                in "feedback" (the teacher will read the attached file directly).

                CLASS MATERIALS:
                %s

                HOMEWORK: %s%s

                STUDENT WORK:
                %s
                """.formatted(
                        brain == null || brain.isBlank() ? "(none provided)" : brain,
                        title,
                        subjectLine,
                        work == null || work.isBlank() ? "(no readable text extracted)" : work);
    }

    /** Models sometimes wrap JSON in ```json fences despite instructions; strip them. */
    private static String stripFences(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }
}
