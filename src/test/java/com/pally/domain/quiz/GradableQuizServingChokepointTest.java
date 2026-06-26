package com.pally.domain.quiz;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architectural guard (same spirit as the child-data ingress + route-existence
 * tests): a served, gradable quiz question may be built ONLY at the serving
 * chokepoint {@code QuizService.serveGradable}, which guarantees two things a
 * new quiz type must not be able to skip:
 * <ol>
 *   <li>the SERVER answer key is persisted (so submit grades authoritatively and
 *       ignores the client map — closes answer <em>tampering</em>), and</li>
 *   <li>{@code correctIndex} is withheld for teacher-graded centre quizzes
 *       (closes answer <em>exposure</em> into teacher-visible mastery).</li>
 * </ol>
 *
 * <p>{@code QuizQuestionResponse.from(..)} is the only way to build a served
 * question, so if any file OTHER than the chokepoint calls it, that path bypassed
 * both guarantees — this test fails it, forcing the new path through the
 * chokepoint instead of silently shipping a gradable quiz without a key.
 */
class GradableQuizServingChokepointTest {

    /// The one file allowed to build a served quiz question.
    private static final String CHOKEPOINT = "QuizService.java";

    @Test
    void onlyTheServingChokepointBuildsGradableQuizQuestions() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                String name = p.getFileName().toString();
                // The DTO defines from(); the chokepoint is the allowed caller.
                if (name.equals("QuizQuestionResponse.java")) continue;
                if (name.equals(CHOKEPOINT)) continue;
                String src = Files.readString(p);
                if (src.contains("QuizQuestionResponse.from(")
                        || src.contains("QuizQuestionResponse::from")) {
                    offenders.add(p.toString());
                }
            }
        }
        assertThat(offenders)
                .as("Only %s (the chokepoint that persists the server key and "
                        + "withholds the answer for teacher-graded quizzes) may "
                        + "build a served quiz question. These paths bypass it:\n%s",
                        CHOKEPOINT, String.join("\n", offenders))
                .isEmpty();
    }
}
