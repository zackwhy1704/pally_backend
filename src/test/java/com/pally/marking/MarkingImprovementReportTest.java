package com.pally.marking;

import com.pally.domain.marking.AgreementScorer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scores the marking-improvement probe's collected drafts with the SHIPPED
 * {@link AgreementScorer} (the ruler production will use) — not ad-hoc grep — and
 * is SELF-CHECKING: it asserts each taught rule had HEADROOM (round-1 adoption
 * low, else the rule is too generic to prove learning) and each control STARTED
 * near zero (else a flat control is meaningless). Emits per-subject
 * PASS/PARTIAL/FAIL with the reason.
 *
 * <p>Run after scripts/marking_improvement_probe.sh:
 *   ./gradlew test --tests com.pally.marking.MarkingImprovementReportTest \
 *     -Dprobe.samples=/abs/samples -Dprobe.manifest=/abs/manifest.tsv
 * In normal CI (no -Dprobe.samples) it SKIPS. If samples exist but are empty
 * (no live keys) it FAILS loudly — never a vacuous pass.
 */
@Tag("probe")
class MarkingImprovementReportTest {

    // Headroom: a taught rule the model already emits >40% of the time unprompted
    // proves nothing. A valid control must start at/below 20% (near-absent).
    private static final double HEADROOM_MAX = 0.40;
    private static final double CONTROL_START_MAX = 0.20;

    record RoundStat(int taught, int control, int total, double meanSim) {}

    record SubjectResult(String subject, RoundStat r1, RoundStat r2,
                         boolean headroomOk, boolean controlValid, String verdict) {}

    @Test
    void marking_assistant_learns_centre_rules_across_subjects() throws IOException {
        String sampleDir = System.getProperty("probe.samples");
        String manifest = System.getProperty("probe.manifest");
        Assumptions.assumeTrue(sampleDir != null && manifest != null,
                "probe-only test; run the shell probe first and pass -Dprobe.samples/-Dprobe.manifest");
        Path samples = Paths.get(sampleDir);
        assertThat(samples).exists();

        List<String> lines = Files.readAllLines(Paths.get(manifest));
        assertThat(lines).as("manifest must list subjects").isNotEmpty();

        List<SubjectResult> results = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split("\t", -1);
            if (f.length < 5) continue;
            String subj = f[0];
            Pattern taught = Pattern.compile(f[2], Pattern.CASE_INSENSITIVE);
            Pattern control = Pattern.compile(f[3], Pattern.CASE_INSENSITIVE);
            String gtComments = f[4];

            RoundStat r1 = scoreRound(samples, subj, 1, taught, control, gtComments);
            RoundStat r2 = scoreRound(samples, subj, 2, taught, control, gtComments);
            // No drafts = no live keys → fail loudly, never a vacuous pass.
            assertThat(r1.total()).as("no round-1 drafts for " + subj + " (live keys missing?)").isGreaterThan(0);
            assertThat(r2.total()).as("no round-2 drafts for " + subj).isGreaterThan(0);

            boolean headroomOk = rate(r1.taught(), r1.total()) <= HEADROOM_MAX;
            boolean controlValid = rate(r1.control(), r1.total()) <= CONTROL_START_MAX;
            boolean learned = rate(r2.taught(), r2.total()) > rate(r1.taught(), r1.total());
            boolean strongLearn = (r2.taught() - r1.taught()) >= Math.max(2, r1.total() / 2);
            boolean controlFlat = rate(r2.control(), r2.total()) <= rate(r1.control(), r1.total()) + 0.2 + 1e-9;

            String verdict;
            if (!headroomOk) verdict = "FAIL — no headroom (round-1 taught " + r1.taught() + "/" + r1.total()
                    + ", rule too generic to prove learning)";
            else if (!controlValid) verdict = "FAIL — control invalid (already present round-1 "
                    + r1.control() + "/" + r1.total() + ")";
            else if (learned && controlFlat && strongLearn) verdict = "PASS";
            else if (learned && controlFlat) verdict = "PARTIAL — learned but weak margin";
            else if (!controlFlat) verdict = "FAIL — control drifted (confound)";
            else verdict = "FAIL — no learning (taught " + r1.taught() + "→" + r2.taught() + ")";

            results.add(new SubjectResult(subj, r1, r2, headroomOk, controlValid, verdict));
        }

        writeReport(samples, results);

        // ── Self-checking assertions ──
        // 1) Every taught rule must have had headroom, every control must have started valid —
        //    else the probe DESIGN is broken (a "pass" would be meaningless).
        for (SubjectResult s : results) {
            assertThat(s.headroomOk())
                    .as(s.subject() + ": taught rule too generic (round-1 " + s.r1().taught() + "/"
                            + s.r1().total() + ") — pick a non-generic rule")
                    .isTrue();
            assertThat(s.controlValid())
                    .as(s.subject() + ": control not genuinely absent at round-1 (" + s.r1().control()
                            + "/" + s.r1().total() + ") — pick an absent control")
                    .isTrue();
        }
        // 2) The known-good positive control (maths "-2 units") must LEARN — round-2 adoption
        //    strictly above round-1. Robust to LLM variance in the MAGNITUDE (some runs 1/5,
        //    some 4/5); a FAIL here means the probe stopped detecting learning at all.
        SubjectResult maths = results.stream().filter(r -> r.subject().equals("maths")).findFirst().orElseThrow();
        assertThat(rate(maths.r2().taught(), maths.r2().total()))
                .as("maths positive-control must LEARN (round2 taught > round1)")
                .isGreaterThan(rate(maths.r1().taught(), maths.r1().total()));
        // 3) No control drifted (no confound), on any subject.
        assertThat(results).allSatisfy(s -> assertThat(s.verdict())
                .as(s.subject() + " control must not drift").doesNotContain("control drifted"));
        // 4) A majority of subjects must reach strong PASS (the aggregate causal claim).
        long passing = results.stream().filter(r -> r.verdict().equals("PASS")).count();
        assertThat(passing).as("most subjects must PASS").isGreaterThanOrEqualTo((results.size() + 1) / 2);
    }

    private double rate(int n, int total) {
        return total == 0 ? 0 : (double) n / total;
    }

    private RoundStat scoreRound(Path dir, String subj, int round, Pattern taught, Pattern control,
                                 String gtComments) throws IOException {
        List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().startsWith(subj + "__r" + round + "__"))
                     .sorted().toList();
        }
        int t = 0, c = 0;
        double simSum = 0;
        for (Path p : files) {
            String draft = Files.readString(p);
            if (taught.matcher(draft).find()) t++;
            if (control.matcher(draft).find()) c++;
            simSum += AgreementScorer.commentSimilarity(draft, gtComments);
        }
        int n = files.size();
        return new RoundStat(t, c, n, n == 0 ? 0 : simSum / n);
    }

    private void writeReport(Path samples, List<SubjectResult> results) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# Marking-assistant improvement — rigorous, self-checking (multi-subject)\n\n");
        md.append("Scored with the shipped AgreementScorer. Each taught rule is NON-GENERIC (round-1 must be "
                + "LOW = headroom) and each control is GENUINELY ABSENT (round-1 must be ~0 = valid) — "
                + "otherwise the cell proves nothing and is FAILed. commentSim = AgreementScorer similarity "
                + "to the teacher's ground-truth comment.\n\n");
        md.append("| subject | taught r1→r2 | headroom? | control r1→r2 | control valid? | commentSim | verdict |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (SubjectResult s : results) {
            md.append(String.format("| %s | %d/%d → **%d/%d** | %s | %d/%d → %d/%d | %s | %.2f→%.2f | %s |%n",
                    s.subject(), s.r1().taught(), s.r1().total(), s.r2().taught(), s.r2().total(),
                    s.headroomOk() ? "yes" : "**NO**",
                    s.r1().control(), s.r1().total(), s.r2().control(), s.r2().total(),
                    s.controlValid() ? "yes" : "**NO**",
                    s.r1().meanSim(), s.r2().meanSim(), s.verdict()));
        }
        long pass = results.stream().filter(r -> r.verdict().equals("PASS")).count();
        md.append("\n**").append(pass).append("/").append(results.size())
          .append(" subjects PASS** (taught rule adopted after training, with a valid absent control held flat).\n");
        md.append("\n_Honesty: N=").append(System.getProperty("probe.n", "5"))
          .append(" drafts/round/subject. Adoption RATES (stochastic model → the rate, not any single draft, "
                  + "is the evidence). A cell only counts if its taught rule had headroom AND its control was "
                  + "genuinely absent — both are asserted, so a PASS is meaningful._\n");
        Path report = samples.getParent().resolve("REPORT.md");
        Files.writeString(report, md.toString());
        System.out.println("[probe] wrote " + report + "\n" + md);
    }
}
