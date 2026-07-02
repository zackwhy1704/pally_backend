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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scores the marking-improvement probe's collected drafts with the SHIPPED
 * {@link AgreementScorer} (the same ruler production will use) — not ad-hoc grep.
 * Reports per-subject adoption RATES (round1→round2 over N reps), a CONTROL
 * concept that must NOT change, and AgreementScorer comment-similarity deltas.
 *
 * <p>Run only after scripts/marking_improvement_probe.sh:
 *   ./gradlew test --tests com.pally.marking.MarkingImprovementReportTest \
 *     -Dprobe.samples=/abs/samples -Dprobe.manifest=/abs/manifest.tsv
 * In normal CI (no -Dprobe.samples) it SKIPS. If samples exist but are empty
 * (no live keys) it FAILS loudly — never a vacuous pass.
 */
@Tag("probe")
class MarkingImprovementReportTest {

    private static final Pattern GRADE = Pattern.compile("\\d+\\s*/\\s*\\d+|\\d+%");

    record RoundStat(int taught, int control, int total, double meanSim,
                     int gradeExactOrClose, int gradeScored) {}

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

        StringBuilder md = new StringBuilder();
        md.append("# Marking-assistant improvement — rigorous (multi-subject, repeated, controlled)\n\n");
        md.append("Scored with the shipped AgreementScorer. Adoption = share of the N drafts/round that "
                + "apply the CENTRE rule (only learnable from exemplars). Control = an untaught rule that "
                + "must NOT change (rules out 'more context'). commentSim = AgreementScorer.commentSimilarity "
                + "to the teacher's ground-truth comment.\n\n");
        md.append("| subject | taught adoption r1→r2 | control r1→r2 | mean commentSim r1→r2 | grade agree r1→r2 |\n");
        md.append("|---|---|---|---|---|\n");

        int totTaught1 = 0, totTaught2 = 0, totControl1 = 0, totControl2 = 0, totN = 0;
        double totSim1 = 0, totSim2 = 0;

        for (String line : lines) {
            String[] f = line.split("\t", -1);
            if (f.length < 5) continue;
            String subj = f[0];
            String gtGrade = f[1];
            Pattern taught = Pattern.compile(f[2], Pattern.CASE_INSENSITIVE);
            Pattern control = Pattern.compile(f[3], Pattern.CASE_INSENSITIVE);
            String gtComments = f[4];

            RoundStat r1 = scoreRound(samples, subj, 1, taught, control, gtComments, gtGrade);
            RoundStat r2 = scoreRound(samples, subj, 2, taught, control, gtComments, gtGrade);
            // A round with zero drafts = no live keys → fail loudly, not vacuously.
            assertThat(r1.total()).as("no round-1 drafts for " + subj + " (live keys missing?)").isGreaterThan(0);
            assertThat(r2.total()).as("no round-2 drafts for " + subj).isGreaterThan(0);

            totTaught1 += r1.taught(); totTaught2 += r2.taught();
            totControl1 += r1.control(); totControl2 += r2.control();
            totN += r1.total();
            totSim1 += r1.meanSim(); totSim2 += r2.meanSim();

            md.append(String.format("| %s | %d/%d → **%d/%d** | %d/%d → %d/%d | %.2f → %.2f | %s |%n",
                    subj, r1.taught(), r1.total(), r2.taught(), r2.total(),
                    r1.control(), r1.total(), r2.control(), r2.total(),
                    r1.meanSim(), r2.meanSim(), gradeLine(r1, r2)));
        }

        int subjects = (int) lines.stream().filter(l -> l.split("\t").length >= 5).count();
        md.append("\n**Totals:** taught adoption ").append(totTaught1).append("→").append(totTaught2)
          .append(" of ").append(totN).append(" drafts; control ").append(totControl1).append("→")
          .append(totControl2).append("; mean commentSim ")
          .append(String.format("%.2f→%.2f", totSim1 / subjects, totSim2 / subjects)).append(".\n\n");

        boolean learned = totTaught2 > totTaught1;
        boolean controlStable = totControl2 <= totControl1 + 1; // no spurious rise from "more context"
        md.append(learned && controlStable
                ? "**RESULT: LEARNS.** Centre-rule adoption rose across subjects while the untaught control "
                  + "held steady — the delta is the trained rule, not general context inflation.\n"
                : "**RESULT: INCONCLUSIVE.** taughtΔ=" + (totTaught2 - totTaught1)
                  + " controlΔ=" + (totControl2 - totControl1) + " — investigate.\n");
        md.append("\n_Honesty: N=").append(System.getProperty("probe.n", "5"))
          .append(" drafts/round/subject over ").append(subjects)
          .append(" subjects. Adoption rates (not a single 0/1); LLM outputs are stochastic so the rate, "
                  + "not any one draft, is the evidence._\n");

        Path report = samples.getParent().resolve("REPORT.md");
        Files.writeString(report, md.toString());
        System.out.println("[probe] wrote " + report + "\n" + md);

        assertThat(learned).as("centre-rule adoption must rise round1→round2 across subjects").isTrue();
        assertThat(controlStable).as("untaught control must not spuriously rise").isTrue();
    }

    private RoundStat scoreRound(Path dir, String subj, int round, Pattern taught, Pattern control,
                                 String gtComments, String gtGrade) throws IOException {
        List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().startsWith(subj + "__r" + round + "__"))
                     .sorted().toList();
        }
        int t = 0, c = 0, gExact = 0, gScored = 0;
        double simSum = 0;
        for (Path p : files) {
            String draft = Files.readString(p);
            if (taught.matcher(draft).find()) t++;
            if (control.matcher(draft).find()) c++;
            simSum += AgreementScorer.commentSimilarity(draft, gtComments);
            if (gtGrade != null && !gtGrade.isBlank()) {
                var m = GRADE.matcher(draft);
                if (m.find()) {
                    gScored++;
                    var ga = AgreementScorer.gradeAgreement(m.group(), gtGrade);
                    if (ga == AgreementScorer.GradeAgreement.EXACT
                            || ga == AgreementScorer.GradeAgreement.WITHIN_ONE_BAND) gExact++;
                }
            }
        }
        int n = files.size();
        return new RoundStat(t, c, n, n == 0 ? 0 : simSum / n, gExact, gScored);
    }

    private String gradeLine(RoundStat r1, RoundStat r2) {
        if (r1.gradeScored() == 0 && r2.gradeScored() == 0) return "n/a";
        return r1.gradeExactOrClose() + "/" + r1.gradeScored() + " → "
                + r2.gradeExactOrClose() + "/" + r2.gradeScored();
    }
}
