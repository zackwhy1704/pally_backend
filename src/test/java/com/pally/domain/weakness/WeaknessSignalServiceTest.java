package com.pally.domain.weakness;

import com.pally.domain.avatar.Subject;
import com.pally.domain.weakness.WeaknessSignalRepository.TopicMastery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The weakness report presents only real, well-attested weak areas. */
class WeaknessSignalServiceTest {

    private final WeaknessSignalService service = new WeaknessSignalService();

    @Test
    void reportsWeakTopicsWithEvidence() {
        String report = service.renderReport(Subject.MATHS, List.of(
                new TopicMastery("fractions", 0.25, 4),   // weak
                new TopicMastery("algebra", 0.9, 5)));     // strong

        assertThat(report).isNotNull();
        assertThat(report).contains("Weak topics").contains("fractions");
        // Evidence is cited: 1/4 correct, 25%.
        assertThat(report).contains("1/4").contains("25%");
        // Strong topics are offered to build on, not targeted.
        assertThat(report).contains("Already strong").contains("algebra");
    }

    @Test
    void returnsNullWhenNothingIsWeak() {
        String report = service.renderReport(Subject.MATHS, List.of(
                new TopicMastery("fractions", 0.9, 5),
                new TopicMastery("algebra", 1.0, 4)));
        assertThat(report).isNull(); // nothing to target → caller skips the rebuild
    }

    @Test
    void ignoresTopicsWithTooFewAttempts() {
        // A single wrong answer isn't yet a weakness — needs MIN_ATTEMPTS.
        String report = service.renderReport(Subject.SCIENCE, List.of(
                new TopicMastery("osmosis", 0.0, 1)));
        assertThat(report).isNull();
    }

    @Test
    void weakestTopicIsListedFirst() {
        String report = service.renderReport(Subject.MATHS, List.of(
                new TopicMastery("ratios", 0.5, 4),
                new TopicMastery("fractions", 0.1, 5)));
        assertThat(report).isNotNull();
        assertThat(report.indexOf("fractions")).isLessThan(report.indexOf("ratios"));
    }
}
