package com.pally.domain.chat;

/**
 * Sealed interface representing a single event emitted during a streaming chat response.
 *
 * <p>Consumers should pattern-match on the three permitted subtypes:
 * <ul>
 *   <li>{@link Token} — carries a text delta from the AI model</li>
 *   <li>{@link Done}  — signals that the stream has completed successfully</li>
 *   <li>{@link Error} — signals an error during streaming</li>
 * </ul>
 */
public sealed interface ChatStreamEvent
        permits ChatStreamEvent.Token, ChatStreamEvent.Done, ChatStreamEvent.Error {

    /** A text fragment emitted incrementally by the AI model. */
    record Token(String text) implements ChatStreamEvent {}

    /** Signals successful completion of the stream. */
    record Done(String sourceFile) implements ChatStreamEvent {}

    /** Signals an error that terminated the stream. */
    record Error(String message) implements ChatStreamEvent {}
}
