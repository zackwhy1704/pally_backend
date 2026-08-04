package com.pally.domain.chat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * detectsFrustration was relocated here from ClaudeContextAssembler (where it
 * computed a signal that a later refactor — SendMessageUseCase's
 * buildBlocksWithSocraticTail — silently discarded before it ever reached the
 * model). Same patterns, same trigger shape as before; this is where the
 * live escape/deflection decision is actually made, so the result can now
 * reach it. See SendMessageUseCase for the wiring.
 */
class TopicClassifierTest {

    private final TopicClassifier classifier = new TopicClassifier();

    @Test
    void detectsFrustration_fewerThan4Messages_false() {
        // Only 3 user turns — should NOT trigger
        List<ChatMessage> history = List.of(
                userMsg("What is photosynthesis?"),
                userMsg("I still don't understand"),
                userMsg("just give me the answer")
        );
        assertThat(classifier.detectsFrustration(history, "tell me!")).isFalse();
    }

    @Test
    void detectsFrustration_4MessagesNoFrustrationSignal_false() {
        List<ChatMessage> history = List.of(
                userMsg("What is photosynthesis?"),
                userMsg("How does it work?"),
                userMsg("What is chlorophyll?"),
                userMsg("And what about sunlight?")
        );
        assertThat(classifier.detectsFrustration(history, "Ok thanks")).isFalse();
    }

    @Test
    void detectsFrustration_4PlusMessagesWithFrustration_true() {
        List<ChatMessage> history = List.of(
                userMsg("What is photosynthesis?"),
                userMsg("How does it work?"),
                userMsg("I still don't understand"),
                userMsg("just give me the answer")
        );
        assertThat(classifier.detectsFrustration(history, "I'm confused")).isTrue();
    }

    @Test
    void detectsFrustration_frustrationInCurrentMessage_true() {
        List<ChatMessage> history = List.of(
                userMsg("What is x?"),
                userMsg("I don't get it"),
                userMsg("Help me"),
                userMsg("More context please")
        );
        // Current message contains frustration signal
        assertThat(classifier.detectsFrustration(history, "tell me the answer already")).isTrue();
    }

    @Test
    void detectsFrustration_emptyHistory_false() {
        assertThat(classifier.detectsFrustration(List.of(), "just give me the answer")).isFalse();
    }

    @Test
    void detectsFrustration_nullHistory_false() {
        assertThat(classifier.detectsFrustration(null, "still confused")).isFalse();
    }

    @Test
    void detectsFrustration_whatIsTheAnswer_keyword_triggers() {
        List<ChatMessage> history = List.of(
                userMsg("q1"), userMsg("q2"), userMsg("q3"), userMsg("q4")
        );
        assertThat(classifier.detectsFrustration(history, "what is the answer")).isTrue();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ChatMessage userMsg(String content) {
        return ChatMessage.reconstitute(
                "id-" + content.hashCode(),
                "avatar-1", "user-1",
                ChatMessage.Role.USER, content, null, Instant.now()
        );
    }
}
