package com.pally.domain.knowledge.groundedness;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Groundedness gate (B3): catches content the model INVENTED — a hard fact in a
 * generated item that is not entailed by the source notes. It judges whether the
 * model <em>added to or distorted</em> the source, NOT whether the source is true
 * (that's the teacher's job).
 *
 * <p>Pipeline, cheap → expensive:
 * <ol>
 *   <li><b>Extract</b> claims deterministically ({@link ClaimExtractor}).</li>
 *   <li><b>Lexical pre-pass</b> (Jaccard-style source coverage, NO LLM): claims
 *       that track the source closely are SUPPORTED for free — the main cost lever
 *       and the main paraphrase false-alarm guard.</li>
 *   <li><b>Entailment</b>: only low-overlap HARD-FACT claims go to ONE batched,
 *       citation-forced {@link EntailmentJudge} call.</li>
 * </ol>
 *
 * <p>Decision (fabrication vs elaboration): SUPPORTED → clean; CONTRADICTED →
 * caller regenerates once then FLAGs; NOT_IN_SOURCE + hard fact → FLAG (likely
 * fabrication); NOT_IN_SOURCE + no hard fact → ALLOW (legitimate lesson-building).
 *
 * <p><b>Honest ceiling:</b> the judge is itself an LLM. It is reliable only
 * RELATIVE to open fact-checking because it is constrained to the source text and
 * must cite — it screens, it does not certify. The teacher remains the verifier;
 * the product claim stays "teacher-reviewed", never "AI-verified correct".
 */
@Service
@Slf4j
public class GroundednessVerifier {

    public enum Action { CLEAN, FLAG, CONTRADICTED, ALLOW }

    public record ClaimVerdict(
            String claim, Action action, EntailmentJudge.Verdict verdict,
            String sourceQuote, boolean hardFact) {}

    public record Report(List<ClaimVerdict> verdicts, int llmCalls) {
        public List<ClaimVerdict> flagged() {
            return verdicts.stream().filter(v -> v.action() == Action.FLAG).toList();
        }
        public List<ClaimVerdict> contradicted() {
            return verdicts.stream().filter(v -> v.action() == Action.CONTRADICTED).toList();
        }
        /** A FLAG or CONTRADICTED claim → the teacher should look. */
        public boolean needsAttention() {
            return verdicts.stream().anyMatch(
                    v -> v.action() == Action.FLAG || v.action() == Action.CONTRADICTED);
        }
    }

    /** Pre-pass cutoff: claim-token coverage by a source sentence ≥ this → SUPPORTED. */
    @Value("${groundedness.high-overlap:0.6}")
    private double highOverlap;

    /** If the running flag rate exceeds this, the gate is likely over-aggressive. */
    @Value("${groundedness.flag-rate-ceiling:0.20}")
    private double flagRateCeiling;

    private final EntailmentJudge judge;

    // Calibration metric: % of checked items that produced a flag.
    private final AtomicLong itemsChecked = new AtomicLong();
    private final AtomicLong itemsFlagged = new AtomicLong();

    public GroundednessVerifier(EntailmentJudge judge) {
        this.judge = judge;
    }

    /**
     * @param sourceText     the source wiki page content (one small page)
     * @param generatedTexts the generated fragments to check (e.g. a quiz's stem +
     *                       answer + explanation, or a module LEARN body)
     */
    public Report check(String sourceText, List<String> generatedTexts) {
        List<Claim> claims = generatedTexts.stream()
                .flatMap(t -> ClaimExtractor.extract(t).stream())
                .toList();

        Set<String> sourceNumbers = numberRuns(sourceText);
        List<ClaimVerdict> verdicts = new ArrayList<>();
        List<Claim> lowOverlap = new ArrayList<>();
        for (Claim c : claims) {
            // The pre-pass absorbs PARAPHRASE — but must NEVER pass a contradiction.
            // A changed digit (1789→1798) is high-overlap yet wrong, so a hard-fact
            // claim only short-circuits when ALL its numbers are present in the
            // source; otherwise it goes to the entailment judge.
            boolean prePassEligible = !c.hardFact() || numbersCovered(c.text(), sourceNumbers);
            if (prePassEligible && sourceCoverage(c.text(), sourceText) >= highOverlap) {
                verdicts.add(new ClaimVerdict(c.text(), Action.CLEAN,
                        EntailmentJudge.Verdict.SUPPORTED, null, c.hardFact()));
            } else {
                lowOverlap.add(c);
            }
        }

        int llmCalls = 0;
        boolean anyHardFact = lowOverlap.stream().anyMatch(Claim::hardFact);
        if (!lowOverlap.isEmpty() && anyHardFact) {
            // EXACTLY ONE batched call — never one per claim. Soft claims ride along
            // for free and are resolved by the decision table (no extra cost).
            List<EntailmentJudge.Entailment> results = judge.judge(sourceText, lowOverlap);
            llmCalls = 1;
            for (int i = 0; i < lowOverlap.size(); i++) {
                Claim c = lowOverlap.get(i);
                EntailmentJudge.Entailment e = i < results.size() ? results.get(i)
                        : new EntailmentJudge.Entailment(EntailmentJudge.Verdict.NOT_IN_SOURCE, null);
                verdicts.add(mapVerdict(c, e));
            }
        } else {
            // No hard fact to verify → everything here is elaboration → ALLOW, 0 calls.
            for (Claim c : lowOverlap) {
                verdicts.add(new ClaimVerdict(c.text(), Action.ALLOW,
                        EntailmentJudge.Verdict.NOT_IN_SOURCE, null, false));
            }
        }

        Report report = new Report(verdicts, llmCalls);
        recordCalibration(report);
        return report;
    }

    private ClaimVerdict mapVerdict(Claim c, EntailmentJudge.Entailment e) {
        return switch (e.verdict()) {
            case SUPPORTED -> new ClaimVerdict(c.text(), Action.CLEAN,
                    EntailmentJudge.Verdict.SUPPORTED, e.sourceQuote(), c.hardFact());
            case CONTRADICTED -> new ClaimVerdict(c.text(), Action.CONTRADICTED,
                    EntailmentJudge.Verdict.CONTRADICTED, e.sourceQuote(), c.hardFact());
            // The crux: NOT_IN_SOURCE → FLAG only when it carries a hard fact;
            // otherwise it's legitimate elaboration → ALLOW (never flag teaching).
            case NOT_IN_SOURCE -> c.hardFact()
                    ? new ClaimVerdict(c.text(), Action.FLAG,
                            EntailmentJudge.Verdict.NOT_IN_SOURCE, null, true)
                    : new ClaimVerdict(c.text(), Action.ALLOW,
                            EntailmentJudge.Verdict.NOT_IN_SOURCE, null, false);
        };
    }

    /**
     * Coverage of the claim's content tokens by the best-matching source sentence.
     * A short claim fully present in a longer source sentence scores high (we want
     * "is this claim's content in the source", not symmetric Jaccard which the
     * length asymmetry would penalise). This absorbs paraphrase before any LLM call.
     */
    double sourceCoverage(String claim, String source) {
        Set<String> ct = tokens(claim);
        if (ct.isEmpty()) return 0.0;
        String[] sents = source.split("[.!?\\n]");
        double best = 0.0;
        for (int i = 0; i < sents.length; i++) {
            // Single sentence AND the adjacent-sentence-pair window: a grounded
            // claim frequently paraphrases TWO consecutive source sentences, and
            // single-sentence coverage under-counts it → a false flag. The pair
            // window (not the whole page — that would gut the gate) absorbs that
            // real false-positive without weakening the hard-fact number check
            // (numbersCovered) or contradiction detection (the judge).
            Set<String> st = new HashSet<>(tokens(sents[i]));
            if (i + 1 < sents.length) st.addAll(tokens(sents[i + 1]));
            if (st.isEmpty()) continue;
            Set<String> inter = new HashSet<>(ct);
            inter.retainAll(st);
            best = Math.max(best, (double) inter.size() / ct.size());
        }
        return best;
    }

    private Set<String> tokens(String s) {
        return Arrays.stream(s.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> t.length() > 2)
                .collect(Collectors.toSet());
    }

    private static final java.util.regex.Pattern NUM = java.util.regex.Pattern.compile("\\d+");

    /** The set of digit-runs in a text (e.g. "began in 1789" → {"1789"}). */
    private Set<String> numberRuns(String text) {
        Set<String> out = new HashSet<>();
        java.util.regex.Matcher m = NUM.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** True iff every number in the claim also appears in the source. */
    private boolean numbersCovered(String claim, Set<String> sourceNumbers) {
        java.util.regex.Matcher m = NUM.matcher(claim);
        while (m.find()) {
            if (!sourceNumbers.contains(m.group())) return false;
        }
        return true;
    }

    private void recordCalibration(Report report) {
        long checked = itemsChecked.incrementAndGet();
        if (report.needsAttention()) itemsFlagged.incrementAndGet();
        // Only judge calibration once there's a meaningful sample.
        if (checked >= 20 && currentFlagRate() > flagRateCeiling) {
            // Observability: surface a SAMPLE of what's actually being flagged so the
            // tune is data-driven (are these real fabrications or false positives?),
            // not a blind threshold change. NB: lowering high-overlap widens pre-pass
            // (fewer flags); raising it flags MORE — the opposite of what "too
            // aggressive" needs. Prefer fixing false positives (coverage precision /
            // hard-fact classifier), never relax flag-rate-ceiling to hide the rate.
            String sample = report.verdicts().stream()
                    .filter(v -> v.action() == Action.FLAG || v.action() == Action.CONTRADICTED)
                    .map(v -> v.action() + ": " + trim(v.claim()))
                    .limit(3)
                    .collect(Collectors.joining(" | "));
            log.warn("[Groundedness] flag rate {}% over {} items exceeds ceiling {}% — "
                    + "review the flagged sample (real fabrication vs false positive): {}",
                    Math.round(currentFlagRate() * 100), checked,
                    Math.round(flagRateCeiling * 100), sample.isEmpty() ? "(none this item)" : sample);
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() <= 90 ? s : s.substring(0, 90) + "…";
    }

    /** Running share of checked items that produced a flag (calibration metric). */
    public double currentFlagRate() {
        long checked = itemsChecked.get();
        return checked == 0 ? 0.0 : (double) itemsFlagged.get() / checked;
    }
}
