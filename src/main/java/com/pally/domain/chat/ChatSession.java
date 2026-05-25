package com.pally.domain.chat;

import com.pally.domain.avatar.TeachingMode;
import com.pally.shared.util.IdGenerator;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Server-side session tracking for Socratic dialogue attempts.
 * One record per avatar per day; tracks the current topic and how many
 * attempts the child has made on that topic.
 */
public final class ChatSession {

    private static final int ESCAPE_HATCH_THRESHOLD = 3;

    private final String id;
    private final String avatarId;
    private String currentTopic;
    private int attemptCount;
    private boolean escapeFired;
    private final LocalDate sessionDate;
    private Instant updatedAt;

    private ChatSession(
            String id, String avatarId, String currentTopic,
            int attemptCount, boolean escapeFired,
            LocalDate sessionDate, Instant updatedAt) {
        this.id = id;
        this.avatarId = avatarId;
        this.currentTopic = currentTopic;
        this.attemptCount = attemptCount;
        this.escapeFired = escapeFired;
        this.sessionDate = sessionDate;
        this.updatedAt = updatedAt;
    }

    public static ChatSession createToday(String avatarId) {
        return new ChatSession(
                IdGenerator.newId(), avatarId, null,
                0, false, LocalDate.now(), Instant.now());
    }

    public static ChatSession reconstitute(
            String id, String avatarId, String currentTopic,
            int attemptCount, boolean escapeFired,
            LocalDate sessionDate, Instant updatedAt) {
        return new ChatSession(id, avatarId, currentTopic, attemptCount,
                escapeFired, sessionDate, updatedAt);
    }

    /** Returns true if escape hatch should fire on the next turn. */
    public boolean shouldEscape(TeachingMode mode) {
        return mode == TeachingMode.TEACHING
                && attemptCount >= ESCAPE_HATCH_THRESHOLD
                && !escapeFired;
    }

    /** Records a new attempt on the given topic. Resets count when topic changes. */
    public void recordAttempt(String topicSlug) {
        if (topicSlug != null && !topicSlug.equals(this.currentTopic)) {
            this.currentTopic = topicSlug;
            this.attemptCount = 0;
            this.escapeFired = false;
        }
        this.attemptCount++;
        this.updatedAt = Instant.now();
    }

    public void markEscapeFired() {
        this.escapeFired = true;
        this.updatedAt = Instant.now();
    }

    public String getId()           { return id; }
    public String getAvatarId()     { return avatarId; }
    public String getCurrentTopic() { return currentTopic; }
    public int getAttemptCount()    { return attemptCount; }
    public boolean isEscapeFired()  { return escapeFired; }
    public LocalDate getSessionDate() { return sessionDate; }
    public Instant getUpdatedAt()   { return updatedAt; }
}
