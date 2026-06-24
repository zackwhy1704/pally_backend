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
 * <p><b>Fails open:</b> if the call or parse fails, claims are treated as
 * SUPPORTED (no flag). A judge outage must never flag every lesson — a noisy gate
 * trains teachers to ignore flags. Mirrors {@code ClaudeRelevanceChecker}'s policy.
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
        try {
            String resp = claude.complete(
                    modelRouter.getHaikuModel(), MAX_TOKENS, buildPrompt(sourceText, claims), "groundedness");
            return parse(resp, claims.size());
        } catch (Exception e) {
            log.warn("[Groundedness] entailment judge unavailable — failing OPEN (no flags): {}",
                    e.getMessage());
            return failOpen(claims.size());
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

    private List<Entailment> parse(String resp, int expected) {
        if (resp == null) return failOpen(expected);
        int start = resp.indexOf('[');
        int end = resp.lastIndexOf(']');
        if (start < 0 || end <= start) return failOpen(expected);
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
            // Shortfall → pad with SUPPORTED (fail open), never under-cite into a flag.
            while (out.size() < expected) out.add(new Entailment(Verdict.SUPPORTED, null));
            return out.subList(0, expected);
        } catch (Exception e) {
            log.warn("[Groundedness] could not parse judge response — failing OPEN: {}", e.getMessage());
            return failOpen(expected);
        }
    }

    private List<Entailment> failOpen(int n) {
        List<Entailment> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Entailment(Verdict.SUPPORTED, null));
        return out;
    }
}
