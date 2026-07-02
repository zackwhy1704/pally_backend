package com.pally.shared.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ONE robust JSON extractor for all AI-model output. Model responses arrive
 * wrapped in prose or markdown fences, or truncated — a fragile
 * `extractJson`+readValue silently yields empty, which is exactly how the PROVE
 * launch blocker happened (0 items → module could never complete). Every AI
 * class must extract through here, never re-roll a private copy (enforced by
 * JsonExtractionGuardTest).
 */
public final class JsonExtraction {

    private JsonExtraction() {}

    /**
     * Slice the first balanced-looking JSON array/object out of a model reply:
     * strip markdown fences, take from the first {@code open} to the last
     * {@code close}. Returns "[]"/"{}" (never raw prose) when none is found, so a
     * downstream parse degrades to empty instead of throwing.
     */
    public static String extractJson(String raw, char open, char close) {
        if (raw == null || raw.isBlank()) return open == '[' ? "[]" : "{}";
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").strip();
        }
        int start = trimmed.indexOf(open);
        int end = trimmed.lastIndexOf(close);
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return open == '[' ? "[]" : "{}";
    }

    /**
     * Parse a JSON array of objects; if truncated (the common failure), salvage
     * every COMPLETE top-level object before the cut-off instead of discarding
     * the whole response. Returns [] on total failure (never throws).
     */
    public static List<Map<String, Object>> parseObjects(ObjectMapper om, String raw) {
        String json = extractJson(raw, '[', ']');
        try {
            List<Map<String, Object>> all = om.readValue(json, new TypeReference<>() {});
            if (all != null && !all.isEmpty()) return all;
        } catch (Exception truncated) {
            // fall through to element-level salvage
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String obj : extractBalancedObjects(raw)) {
            try {
                out.add(om.readValue(obj, new TypeReference<>() {}));
            } catch (Exception skip) {
                // a malformed / incomplete object — skip just this one
            }
        }
        return out;
    }

    /** Parse a single JSON object leniently; empty map on failure (never throws). */
    public static Map<String, Object> parseObject(ObjectMapper om, String raw) {
        try {
            Map<String, Object> m = om.readValue(extractJson(raw, '{', '}'), new TypeReference<>() {});
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Every balanced top-level {...} object in a (possibly truncated) string. */
    public static List<String> extractBalancedObjects(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        int depth = 0, start = -1;
        boolean inStr = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) { out.add(s.substring(start, i + 1)); start = -1; }
            }
        }
        return out;
    }
}
