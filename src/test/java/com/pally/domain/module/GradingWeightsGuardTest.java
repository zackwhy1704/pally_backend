package com.pally.domain.module;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * DECK GUARD. These trust weights get quoted externally as evidence that mastery is
 * trust-weighted — "a self-reported answer moves mastery roughly a third as much as
 * a server-verified one". This test exists so drift fails loudly HERE rather than
 * silently invalidating an external claim.
 *
 * <p>If this goes red, the number being quoted is wrong. Fix the config or fix the
 * claim — do NOT "fix" the test.
 *
 * <p><b>What this can and cannot prove.</b> {@link GradingWeights} is
 * {@code @ConfigurationProperties("grading")}-driven, so the live value has two
 * drift paths. This test covers BOTH that are checkable at build time:
 * <ol>
 *   <li>the compiled field default changing, and</li>
 *   <li>an override appearing in {@code src/main/resources}.</li>
 * </ol>
 * It CANNOT prove the value running on Railway: a {@code GRADING_SELF_REPORT_WEIGHT}
 * environment variable would override both, and no build-time test can observe that.
 * Confirming the deployed value stays a deploy-verification step, not something this
 * green tick establishes.
 */
class GradingWeightsGuardTest {

    @Test
    void selfReportWeight_defaultsToExactly_0_30() {
        assertThat(new GradingWeights().getSelfReportWeight())
                .as("grading.self-report-weight — quoted externally as 0.30")
                .isEqualTo(0.30, within(1e-9));
    }

    @Test
    void deterministicWeight_isExactly_1_0() {
        assertThat(new GradingWeights().weightFor(GradingSignal.DETERMINISTIC))
                .as("DETERMINISTIC is full trust by definition")
                .isEqualTo(1.0, within(1e-9));
    }

    @Test
    void selfReportWeight_resolvesThroughWeightFor_notJustTheGetter() {
        assertThat(new GradingWeights().weightFor(GradingSignal.SELF_REPORT))
                .isEqualTo(0.30, within(1e-9));
    }

    @Test
    void ungradedContributesNothing() {
        assertThat(new GradingWeights().weightFor(GradingSignal.UNGRADED))
                .as("UNGRADED must never move mastery — never a false 0")
                .isEqualTo(0.0, within(1e-9));
    }

    @Test
    void legacyNullSignal_stillCarriesFullWeight_soTheAuditMustSurfaceItSeparately() {
        // Not a bug — deliberate, to preserve existing students' mastery. But it means
        // full-weight evidence exists that was never actually verified, which is why
        // MasteryAuditResponse reports LEGACY_UNTYPED as its own tier rather than
        // folding it into DETERMINISTIC.
        assertThat(new GradingWeights().weightFor(null)).isEqualTo(1.0, within(1e-9));
    }

    @Test
    void noConfigFileOverridesTheSelfReportWeight() throws IOException {
        // The second drift path: a property in src/main/resources silently replacing
        // the compiled default. Caught here so the quoted 0.30 can't be invalidated
        // by a one-line yml edit that touches no Java.
        Path resources = Paths.get("src/main/resources");
        try (Stream<Path> files = Files.walk(resources)) {
            List<String> offenders = files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".properties");
                    })
                    .filter(p -> {
                        try {
                            String body = Files.readString(p);
                            return body.contains("self-report-weight")
                                    || body.contains("selfReportWeight");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(Path::toString)
                    .toList();

            assertThat(offenders)
                    .as("a config override would silently change the externally-quoted 0.30")
                    .isEmpty();
        }
    }
}
