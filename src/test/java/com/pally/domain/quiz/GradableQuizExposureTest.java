package com.pally.domain.quiz;

import com.pally.domain.quiz.dto.QuizQuestionResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes the answer-EXPOSURE class, not the third instance of it.
 *
 * <p>Twice now a field on {@link QuizQuestionResponse} leaked the answer to a
 * teacher-graded (centre) quiz: {@code correctIndex} (found by the exposure
 * audit) and {@code explanation} (found by the symmetry check — it reveals the
 * answer just like the index, e.g. "3 out of 8"). Both were found by LOOKING,
 * not by exhaustion. So this is a known class: any field on the served-question
 * DTO that reveals or implies the correct option.
 *
 * <p>This test forces every field to be consciously classified. A NEW field on
 * the DTO fails it until someone decides which side of the wall it's on — SAFE
 * (shipped to centre students) or WITHHELD (null pre-submit, returned
 * post-submit in {@code QuizResult.feedback}). That's the version that prevents
 * a third leak instead of waiting for someone to find it.
 */
class GradableQuizExposureTest {

    /// Fields a teacher-graded quiz MAY ship — none reveal/imply the correct option.
    private static final Set<String> SAFE = Set.of(
            "id",             // opaque question id
            "question",       // the prompt the student must read to answer
            "options",        // the choices they pick from
            "sourcePageSlug"  // topic pointer, NOT the answer — the question text
                              // usually names the topic anyway; reveals the
                              // subject, never which option is correct
    );

    /// Fields that reveal/justify the answer — withheld pre-submit, revealed in
    /// the post-submit feedback instead.
    private static final Set<String> WITHHELD = Set.of(
            "correctIndex",
            "explanation"
    );

    @Test
    void everyServedQuizField_isConsciouslyClassifiedSafeOrWithheld() {
        Set<String> declared = Arrays.stream(
                        QuizQuestionResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        Set<String> classified = new HashSet<>(SAFE);
        classified.addAll(WITHHELD);

        assertThat(declared)
                .as("A NEW field on QuizQuestionResponse must be classified SAFE "
                    + "(doesn't reveal the answer) or WITHHELD (does) for a "
                    + "teacher-graded quiz, AND handled in "
                    + "QuizQuestionResponse.from / QuizService.serveGradable. "
                    + "Could a student read the answer off this field? Add it to "
                    + "SAFE or WITHHELD here once you've decided.")
                .isEqualTo(classified);
    }

    @Test
    void withheldFields_areNull_forATeacherGradedQuiz() {
        QuizQuestion q = new QuizQuestion(
                "id-1", "av", "2+2?", List.of("3", "4", "5"), 1, "slug", "4 is 2+2");
        // exposeKey == false → teacher-graded path.
        QuizQuestionResponse served = QuizQuestionResponse.from(q, false);

        assertThat(served.correctIndex()).as("answer index withheld").isNull();
        assertThat(served.explanation()).as("explanation withheld").isNull();
        // Safe fields still present so the student can actually take the quiz.
        assertThat(served.question()).isEqualTo("2+2?");
        assertThat(served.options()).isNotEmpty();
        assertThat(served.sourcePageSlug()).isEqualTo("slug");
    }
}
