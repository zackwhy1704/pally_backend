package com.pally.shared.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The one robust extractor: survives prose, markdown fences, and truncation. */
class JsonExtractionTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void parseObjects_survivesProseAndMarkdownWrapping() {
        String raw = "Sure! Here you go:\n```json\n"
                + "[{\"q\":\"a\"},{\"q\":\"b\"}]\n```";
        var out = JsonExtraction.parseObjects(om, raw);
        assertThat(out).hasSize(2);
    }

    @Test
    void parseObjects_salvagesCompleteObjectsFromTruncation() {
        // Array cut off mid-third-object → the two complete ones must survive.
        String truncated = "[{\"q\":\"a\"},{\"q\":\"b\"},{\"q\":\"c";
        var out = JsonExtraction.parseObjects(om, truncated);
        assertThat(out).hasSize(2);
    }

    @Test
    void parseObjects_returnsEmptyOnPureProse_neverThrows() {
        assertThat(JsonExtraction.parseObjects(om, "no json at all here")).isEmpty();
        assertThat(JsonExtraction.parseObjects(om, null)).isEmpty();
    }

    @Test
    void parseObject_survivesProseWrappedObject() {
        var m = JsonExtraction.parseObject(om, "Result: {\"grade\":\"3/5\",\"ok\":true} done");
        assertThat(m).containsEntry("grade", "3/5");
    }

    @Test
    void extractJson_stripsFences_andFallsBackToEmpty() {
        assertThat(JsonExtraction.extractJson("```json\n[1,2]\n```", '[', ']')).isEqualTo("[1,2]");
        assertThat(JsonExtraction.extractJson("nope", '[', ']')).isEqualTo("[]");
        assertThat(JsonExtraction.extractJson(null, '{', '}')).isEqualTo("{}");
    }
}
