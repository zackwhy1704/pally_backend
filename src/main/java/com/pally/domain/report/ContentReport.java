package com.pally.domain.report;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;

/**
 * A user's report of an AI (Mochi) chat message. Self-contained incident record: it carries the
 * reported {@code messageText} VERBATIM rather than a foreign key to a chat message, so it does not
 * depend on that message being persisted (the client id at report time is a temp id) and survives
 * deletion of the avatar/message. {@code comment} and {@code clientMessageId} are nullable.
 */
public record ContentReport(
        String id,
        String avatarId,
        String userId,
        ContentReportReason reason,
        String comment,
        String messageText,
        String clientMessageId,
        Instant createdAt) {

    public static ContentReport of(String avatarId, String userId, ContentReportReason reason,
                                   String comment, String messageText, String clientMessageId) {
        return new ContentReport(IdGenerator.newId(), avatarId, userId, reason,
                comment, messageText, clientMessageId, Instant.now());
    }
}
