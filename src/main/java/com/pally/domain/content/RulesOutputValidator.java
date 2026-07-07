package com.pally.domain.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.marking.MarkingReference;
import com.pally.domain.module.ContentItemType;
import com.pally.domain.module.ModuleContentItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/// Phase 3 OutputValidator: real per-OutputType rules that DROP unusable output at the
/// persist boundary, so a blank/half-parsed item, an empty report, or a text-less marking
/// reference never reaches the DB. Replaces {@link PassThroughOutputValidator} as the wired
/// bean.
///
/// SCOPE (deliberate, conservative): the rule set is the VERIFIED substantive-content
/// contract (required fields present + non-blank), the exact class of failure that shipped
/// blank drafts before (see {@code ModuleStageFallbackGuardTest}'s B-4 assertions — this
/// mirrors them at runtime). It intentionally does NOT apply fuzzy "looks like raw markdown /
/// truncated" heuristics: on the content hot path a false-positive drop silently loses valid
/// teacher/student content, which is worse than the narrow garbage those heuristics might
/// catch. The generators already summarise raw fragments away (see
/// {@code ModuleContentGenerator.summarizeForFallback}) and always fall back to a substantive
/// item, so the residual risk here is a genuinely blank field — which is what this rejects.
///
/// STRUCTURAL COVERAGE (the codebase's own Phase-3 requirement): dispatch is an EXHAUSTIVE
/// {@code switch} expression over {@link OutputType} — adding a new type without a rule is a
/// COMPILE error, not a silent pass-through. This is the guard against the exact blind spot
/// (an enumerated list inheriting the author's misses) that let blank drafts ship.
@Component
public class RulesOutputValidator implements OutputValidator {

    /// A report shorter than this is treated as empty/failed prose rather than a narrative.
    /// The valid "no quiz data yet…" state is ~90 chars, so this floor never drops it.
    private static final int MIN_REPORT_CHARS = 20;

    private final ObjectMapper mapper;

    public RulesOutputValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public <T> List<T> retainValid(List<T> outputs, OutputType type) {
        if (outputs == null || outputs.isEmpty()) return outputs;
        return outputs.stream().filter(o -> isValid(o, type)).toList();
    }

    /// Exhaustive over OutputType — a new constant without a case fails to compile.
    private boolean isValid(Object o, OutputType type) {
        return switch (type) {
            case MODULE_ITEM -> isValidModuleItem(o);
            case REPORT -> isValidReport(o);
            case MARKING_REFERENCE -> isValidMarkingReference(o);
        };
    }

    // ── MODULE_ITEM — the B-4 substantive contract, per ContentItemType ──────────
    private boolean isValidModuleItem(Object o) {
        // Only ModuleContentItem flows here in production; anything else we can't judge,
        // so keep it (fail-open on type mismatch, never invent a drop).
        if (!(o instanceof ModuleContentItem item)) return true;
        ContentItemType t;
        try {
            t = ContentItemType.valueOf(item.getType());
        } catch (IllegalArgumentException | NullPointerException e) {
            return false; // unknown/absent type = malformed
        }
        Map<String, Object> c = parse(item.getContentJson());
        Map<String, Object> a = parse(item.getAnswerJson());
        return switch (t) {
            case MICRO_CARD -> nonBlank(c, "title") && nonBlank(c, "body");
            case HOT_TAKE -> nonBlank(c, "statement")
                    && a.get("isTrue") != null && nonBlank(a, "explanation");
            case SPOT_MISTAKE -> nonBlank(c, "problem") && nonBlank(c, "wrongSolution")
                    && nonBlank(a, "errorDescription") && nonBlank(a, "correctSolution");
            case CHALLENGE -> nonBlank(c, "question")
                    && nonBlank(a, "answer") && nonBlank(a, "explanation");
            // PROVE gates module completion; the student-facing stem is the load-bearing
            // field (expectedKeyPoints live in answer_json for evaluation).
            case PROVE_QUESTION -> nonBlank(c, "question");
        };
    }

    // ── REPORT — free-form narrative prose; require substance, not a rigid schema ─
    private boolean isValidReport(Object o) {
        return o instanceof String s && s.strip().length() >= MIN_REPORT_CHARS;
    }

    // ── MARKING_REFERENCE — must carry SOME signal (text OR a stored file) ───────
    // NOT "must have extracted text": MarkingReferenceService deliberately allows a
    // text-less reference — an unreadable/scanned paper is still a viewable artifact the
    // teacher chose to add (documented on create()). So the quality floor is that the
    // reference isn't wholly empty (no text AND no file), which respects that design while
    // still rejecting a genuinely contentless reference.
    private boolean isValidMarkingReference(Object o) {
        if (!(o instanceof MarkingReference r)) return true;
        boolean hasText = r.getExtractedText() != null && !r.getExtractedText().isBlank();
        boolean hasFile = r.getFiles() != null && !r.getFiles().isEmpty();
        return hasText || hasFile;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of(); // unparseable → treated as no fields → invalid
        }
    }

    private static boolean nonBlank(Map<String, Object> m, String key) {
        return m.get(key) instanceof String s && !s.isBlank();
    }
}
