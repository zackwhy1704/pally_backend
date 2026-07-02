package com.pally.domain.weakness;

import com.pally.domain.avatar.Subject;
import com.pally.domain.weakness.WeaknessSignalRepository.TopicMastery;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Renders a student's performance signals into the plain-text "performance
 * report" that the WEAKNESS_PROFILE compiler head compiles into weakness pages.
 * Pure + deterministic (no I/O) so it's trivially testable — the compiler does
 * the structuring; this just presents the evidence honestly.
 */
@Service
public class WeaknessSignalService {

    /** A topic is "weak" below this correctness ratio. */
    static final double WEAK_RATIO = 0.6;
    /** A topic is "strong" at/above this ratio (used to say what to build on). */
    static final double STRONG_RATIO = 0.85;
    /** Need at least this many attempts before a ratio is worth reporting. */
    static final int MIN_ATTEMPTS = 2;

    /**
     * Builds the report, or returns null when there isn't enough signal to
     * bother compiling (caller then skips the rebuild).
     */
    /** The weak topics (weakest-first) — the shared basis for the report + the
     *  debounce signature. Filters out under-attested topics. */
    public List<TopicMastery> weakTopics(List<TopicMastery> mastery) {
        return mastery.stream()
                .filter(m -> m.topicSlug() != null && !m.topicSlug().isBlank())
                .filter(m -> m.attempts() >= MIN_ATTEMPTS)
                .filter(m -> m.correctRatio() < WEAK_RATIO)
                .sorted((a, b) -> Double.compare(a.correctRatio(), b.correctRatio()))
                .toList();
    }

    /** Sorted weak-topic slugs — the debounce signature for a recompile trigger. */
    public List<String> weakSlugs(List<TopicMastery> mastery) {
        return weakTopics(mastery).stream()
                .map(TopicMastery::topicSlug)
                .sorted()
                .toList();
    }

    public String renderReport(Subject subject, List<TopicMastery> mastery) {
        List<TopicMastery> usable = mastery.stream()
                .filter(m -> m.topicSlug() != null && !m.topicSlug().isBlank())
                .filter(m -> m.attempts() >= MIN_ATTEMPTS)
                .toList();
        List<TopicMastery> weak = weakTopics(mastery);
        if (weak.isEmpty()) return null; // nothing to target — skip

        List<TopicMastery> strong = usable.stream()
                .filter(m -> m.correctRatio() >= STRONG_RATIO)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("Student performance signals for ")
          .append(subject.label()).append(".\n\n");
        sb.append("## Weak topics (target these)\n");
        for (TopicMastery m : weak) {
            sb.append("- ").append(m.topicSlug())
              .append(": ").append(correct(m))
              .append(" correct over ").append(m.attempts())
              .append(" attempts (").append(pct(m.correctRatio())).append("%).\n");
        }
        if (!strong.isEmpty()) {
            sb.append("\n## Already strong (build on these)\n");
            for (TopicMastery m : strong) {
                sb.append("- ").append(m.topicSlug())
                  .append(": ").append(pct(m.correctRatio())).append("% over ")
                  .append(m.attempts()).append(" attempts.\n");
            }
        }
        return sb.toString();
    }

    private static int pct(double ratio) {
        return (int) Math.round(ratio * 100);
    }

    /** "N/M" correct-of-attempts, reconstructed from the ratio. */
    private static String correct(TopicMastery m) {
        int right = (int) Math.round(m.correctRatio() * m.attempts());
        return right + "/" + m.attempts();
    }
}
