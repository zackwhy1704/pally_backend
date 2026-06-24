package com.pally.domain.knowledge.groundedness;

import java.util.List;

/**
 * Domain port: judges, using ONLY the provided source text, whether each claim is
 * entailed. The adapter forces a source citation for SUPPORTED/CONTRADICTED, which
 * pins the judge to the text (verifying "does the source say this?") rather than
 * its own world knowledge. Implementations live in {@code infrastructure/ai}.
 */
public interface EntailmentJudge {

    enum Verdict { SUPPORTED, CONTRADICTED, NOT_IN_SOURCE }

    /**
     * @param sourceText the single source wiki page's content
     * @param claims     the low-overlap claims to classify (ONE batched call)
     * @return one result per claim, in the same order
     */
    List<Entailment> judge(String sourceText, List<Claim> claims);

    /** A judged claim. {@code sourceQuote} is the cited source sentence (or null). */
    record Entailment(Verdict verdict, String sourceQuote) {}
}
