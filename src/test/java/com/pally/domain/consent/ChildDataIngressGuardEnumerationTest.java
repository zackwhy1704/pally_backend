package com.pally.domain.consent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The architectural guard that KILLS the fail-open class: every child-data ingress
 * (the Step-0 list) must route through the single {@link ConsentGuard#requireChildDataIngressConsent}
 * chokepoint, and the old fail-open {@code requireGuardianIfUnder13} must be GONE so
 * nothing can call the weak variant.
 *
 * <p>A NEW ingress that ships child-authored text/images to a model or the DB MUST be
 * added to {@link #INGRESS_SOURCES} AND call the guard — otherwise this test fails,
 * forcing the decision instead of letting a new path silently default to fail-open.
 */
class ChildDataIngressGuardEnumerationTest {

    /// Registry of child-data ingress (note upload, free AI chat, photo-question, chunk
    /// compile). Add a new ingress here; the assertion then forces it to call the guard.
    /// NOTE: CompileChunkUseCase was ADDED 2026-07-10 — it had been MISSING from this list,
    /// so the enumeration never enforced the invariant on it and it shipped with only the
    /// weak requireAiConsent (the live-test gap). The mechanical test below now makes an
    /// omission impossible: a hand-kept registry inherits the author's blind spots.
    private static final List<String> INGRESS_SOURCES = List.of(
            "src/main/java/com/pally/domain/knowledge/usecase/UploadFileUseCase.java",
            "src/main/java/com/pally/domain/knowledge/usecase/CompileChunkUseCase.java",
            "src/main/java/com/pally/domain/chat/usecase/SendMessageUseCase.java",
            "src/main/java/com/pally/domain/chat/usecase/SolvePhotoQuestionsUseCase.java",
            "src/main/java/com/pally/api/chat/ChatController.java");

    @Test
    void everyChildDataIngress_routesThroughTheSingleGuard() throws IOException {
        for (String path : INGRESS_SOURCES) {
            String src = Files.readString(Path.of(path));
            assertThat(src)
                    .as("%s is child-data ingress and MUST call "
                            + "consentGuard.requireChildDataIngressConsent at entry", path)
                    .contains("requireChildDataIngressConsent");
        }
    }

    /**
     * MECHANICAL family invariant (no hand-kept list to forget): every source file that calls
     * {@code requireAiConsent} directly is a child-data ingress route, so it MUST also call
     * {@code requireChildDataIngressConsent} BEFORE it — the strong (verified-parental) gate
     * precedes the weak (self-grant-satisfiable) AI-disclosure ack. ConsentGuard itself is
     * excluded (it defines both). This is what would have caught the CompileChunkUseCase gap
     * automatically, and catches the sixth ingress path someone adds next quarter.
     */
    @Test
    void everyRequireAiConsentCaller_callsTheStrongGateFirst() throws IOException {
        Path root = Path.of("src/main/java/com/pally");
        List<Path> offenders = new java.util.ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path p : paths.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.getFileName().toString().equals("ConsentGuard.java"))
                    .toList()) {
                String src = Files.readString(p);
                int weak = src.indexOf("requireAiConsent(");
                if (weak < 0) continue; // not an AI-ingress caller
                int strong = src.indexOf("requireChildDataIngressConsent(");
                if (strong < 0 || strong > weak) {
                    offenders.add(p); // missing the strong gate, or it comes AFTER the weak one
                }
            }
        }
        assertThat(offenders)
                .as("every requireAiConsent caller (a child-data ingress) must call "
                        + "requireChildDataIngressConsent FIRST — these do not")
                .isEmpty();
    }

    @Test
    void failOpenGuardianGate_isRemoved_soNothingCanCallTheWeakVariant() {
        List<String> methodNames = Arrays.stream(ConsentGuard.class.getMethods())
                .map(Method::getName)
                .toList();
        assertThat(methodNames)
                .as("requireGuardianIfUnder13 (fail-open on unknown age) must be deleted, "
                        + "not kept alongside the default-deny guard")
                .doesNotContain("requireGuardianIfUnder13")
                .contains("requireChildDataIngressConsent");
    }
}
