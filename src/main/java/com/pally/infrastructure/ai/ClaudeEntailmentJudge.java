package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.groundedness.Claim;
import com.pally.domain.knowledge.groundedness.EntailmentJudge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Source-only, citation-forced entailment judge (the B3 anti-hallucination call).
 * One batched Haiku call for all claims of an item. The forced source citation
 * pins the model to the provided text, not its priors.
 *
 * <p><b>Fails closed (after one retry):</b> if the call or parse fails, we retry
 * ONCE; if it still can't be parsed, the claims are returned NOT_IN_SOURCE so a
 * hard fact the judge could not verify is FLAGGED for teacher review rather than
 * silently passed. This is only safe because the groundedness gate is now scoped
 * to fact-claiming item types (#1) and headings are filtered out (#2) — removing
 * the false-positive flood that would otherwise make fail-closed over-reject.
 * (Previously failed OPEN → a judge outage silently passed every unverified fact.)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClaudeEntailmentJudge implements EntailmentJudge {

    private static final int MAX_TOKENS = 1024;

    private final ClaudeApiClient claude;
    private final ModelRouter modelRouter;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<Entailment> judge(String sourceText, List<Claim> claims) {
        if (claims == null || claims.isEmpty()) return List.of();
        List<Entailment> result = attempt(sourceText, claims);
        if (result == null) {
            log.warn("[Groundedness] entailment judge attempt 1 failed — retrying once");
            result = attempt(sourceText, claims);
        }
        if (result == null) {
            // Both attempts failed → fail CLOSED: an unverifiable hard fact is
            // flagged for teacher review, never silently passed. Safe post-#1/#2.
            log.warn("[Groundedness] judge unparseable after retry — failing CLOSED "
                    + "({} claims → NOT_IN_SOURCE)", claims.size());
            return failClosed(claims.size());
        }
        return result;
    }

    /** One call+parse attempt. Returns {@code null} on a call failure or an
     * unparseable response so the caller can retry, then fail closed. */
    private List<Entailment> attempt(String sourceText, List<Claim> claims) {
        try {
            String resp = claude.complete(
                    modelRouter.getHaikuModel(), MAX_TOKENS, buildPrompt(sourceText, claims), "groundedness");
            return parse(resp, claims.size());
        } catch (Exception e) {
            log.warn("[Groundedness] entailment judge call failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String source, List<Claim> claims) {
        StringBuilder cl = new StringBuilder();
        for (int i = 0; i < claims.size(); i++) {
            cl.append(i + 1).append(". ").append(claims.get(i).text()).append('\n');
        }
        return """
                You are a strict source-grounding checker. Using ONLY the SOURCE TEXT
                below — NOT your own knowledge — classify each CLAIM.
                For SUPPORTED or CONTRADICTED you MUST quote the exact source sentence.
                If you cannot quote a supporting sentence, it is NOT_IN_SOURCE.
                Do NOT infer from world knowledge.

                Respond with ONLY a JSON array, one object per claim IN ORDER:
                [{"verdict":"SUPPORTED|CONTRADICTED|NOT_IN_SOURCE","sourceQuote":"<exact source sentence, or null>"}]

                SOURCE TEXT:
                %s

                CLAIMS:
                %s
                """.formatted(source, cl);
    }

    /** Parses the judge response, or returns {@code null} if it is unparseable
     * (so the caller retries, then fails closed). */
    private List<Entailment> parse(String resp, int expected) {
        if (resp == null) return null;
        int start = resp.indexOf('[');
        int end = resp.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode arr = mapper.readTree(resp.substring(start, end + 1));
            List<Entailment> out = new ArrayList<>();
            for (JsonNode n : arr) {
                Verdict v = switch (n.path("verdict").asText("NOT_IN_SOURCE").toUpperCase()) {
                    case "SUPPORTED" -> Verdict.SUPPORTED;
                    case "CONTRADICTED" -> Verdict.CONTRADICTED;
                    default -> Verdict.NOT_IN_SOURCE;
                };
                String quote = n.hasNonNull("sourceQuote") ? n.get("sourceQuote").asText() : null;
                out.add(new Entailment(v, quote));
            }
            if (out.isEmpty()) return null; // empty array = nothing judged → retry/fail-closed
            // Shortfall → pad NOT_IN_SOURCE (fail closed): an un-echoed claim is
            // unverified, so flag its hard facts for review rather than pass them.
            while (out.size() < expected) out.add(new Entailment(Verdict.NOT_IN_SOURCE, null));
            return out.subList(0, expected);
        } catch (Exception e) {
            log.warn("[Groundedness] could not parse judge response: {}", e.getMessage());
            return null;
        }
    }

    private List<Entailment> failClosed(int n) {
        List<Entailment> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Entailment(Verdict.NOT_IN_SOURCE, null));
        return out;
    }
}
