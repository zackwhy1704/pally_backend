package com.pally.domain.avatar;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the topically-bounded predicate that drives the relevance topic-gate bypass:
/// GENERAL (no coherent topic) is unbounded; every real subject is bounded. A new subject
/// defaults to bounded (the safe default — it keeps the topic gate), and this test makes
/// that choice explicit so adding an unbounded subject is a conscious edit, not a silent one.
class SubjectTest {

    @Test
    void generalIsTopicallyUnbounded_everyRealSubjectIsBounded() {
        assertThat(Subject.GENERAL.isTopicallyBounded())
                .as("GENERAL has no topic to be off-topic from")
                .isFalse();

        for (Subject s : Subject.values()) {
            if (s != Subject.GENERAL) {
                assertThat(s.isTopicallyBounded())
                        .as("%s is a real subject and must gate on topic relevance", s)
                        .isTrue();
            }
        }
    }
}
