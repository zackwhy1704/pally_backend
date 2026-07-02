package com.pally.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard: no class may re-roll its own private JSON extractor. Every AI class must
 * use the shared {@code JsonExtraction}. History: the PROVE launch blocker came
 * from a fragile private extractJson in one place while the robust one lived in
 * another — N private copies = the bug class recurs. Zero private copies allowed.
 */
class JsonExtractionGuardTest {

    private static final Path MAIN = Paths.get("src/main/java");
    private static final Pattern PRIVATE_EXTRACT =
            Pattern.compile("private\\s+String\\s+extractJson\\s*\\(");

    @Test
    void noPrivateExtractJson_useSharedJsonExtraction() throws IOException {
        Set<String> offenders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path f : javaFiles) {
                if (f.getFileName().toString().equals("JsonExtraction.java")) continue;
                if (PRIVATE_EXTRACT.matcher(Files.readString(f)).find()) {
                    offenders.add(MAIN.relativize(f).toString());
                }
            }
        }
        assertThat(offenders)
                .as("private extractJson copies — use com.pally.shared.json.JsonExtraction instead")
                .isEmpty();
    }
}
