package com.pally.domain.challenge;

import java.time.Instant;

/**
 * Domain model for a teacher-posted class challenge. {@code options} is the raw
 * JSON-array string for MCQ (null for free-text) — the same representation stored
 * in the DB; (de)serialization to {@code List<String>} happens at the API edge.
 * Mutable {@code notified} mirrors the reveal-sweep dedup flag.
 */
public class Challenge {

    private final String id;
    private final String classId;
    private final String question;
    private final String options;
    private final String answer;
    private final Instant revealAt;
    private final String createdBy;
    private final Instant createdAt;
    private boolean notified;

    public Challenge(String id, String classId, String question, String options,
                     String answer, Instant revealAt, String createdBy,
                     Instant createdAt, boolean notified) {
        this.id = id;
        this.classId = classId;
        this.question = question;
        this.options = options;
        this.answer = answer;
        this.revealAt = revealAt;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.notified = notified;
    }

    public String getId() { return id; }
    public String getClassId() { return classId; }
    public String getQuestion() { return question; }
    public String getOptions() { return options; }
    public String getAnswer() { return answer; }
    public Instant getRevealAt() { return revealAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isNotified() { return notified; }
    public void setNotified(boolean notified) { this.notified = notified; }

    /** True when the reveal time has passed. */
    public boolean revealed() {
        return revealAt != null && !revealAt.isAfter(Instant.now());
    }
}
