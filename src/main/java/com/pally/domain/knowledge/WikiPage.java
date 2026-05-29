package com.pally.domain.knowledge;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;

/**
 * Domain entity for a single markdown wiki page belonging to an avatar.
 */
public final class WikiPage {

    public enum Certainty { INFERRED, VERIFIED, UNCERTAIN }

    public enum Status { ACTIVE, ARCHIVED, REVIEW }

    private final String id;
    private final String avatarId;
    private final String slug;
    private String title;
    private String content;
    private Certainty certainty;
    private Instant updatedAt;
    private int qualityScore;
    private String humanCorrection;
    private Instant correctionAt;
    private boolean humanVerified;
    // Harness fields
    private Instant lastRetrievedAt;
    private int quizUseCount;
    private double certaintyScore;
    private Status status;
    private boolean reviewRequired;
    private String prerequisiteSlugs;
    private boolean hasConflict;

    private WikiPage(
            String id, String avatarId, String slug,
            String title, String content, Certainty certainty, Instant updatedAt,
            int qualityScore, String humanCorrection, Instant correctionAt, boolean humanVerified,
            Instant lastRetrievedAt, int quizUseCount, double certaintyScore,
            Status status, boolean reviewRequired, String prerequisiteSlugs,
            boolean hasConflict
    ) {
        this.id = id;
        this.avatarId = avatarId;
        this.slug = slug;
        this.title = title;
        this.content = content;
        this.certainty = certainty;
        this.updatedAt = updatedAt;
        this.qualityScore = qualityScore;
        this.humanCorrection = humanCorrection;
        this.correctionAt = correctionAt;
        this.humanVerified = humanVerified;
        this.lastRetrievedAt = lastRetrievedAt;
        this.quizUseCount = quizUseCount;
        this.certaintyScore = certaintyScore;
        this.status = status;
        this.reviewRequired = reviewRequired;
        this.prerequisiteSlugs = prerequisiteSlugs;
        // Previously hardcoded to false — a latent bug that swallowed the
        // passed-in flag and made hasConflict always reset on rehydrate.
        this.hasConflict = hasConflict;
    }

    private WikiPage(
            String id, String avatarId, String slug,
            String title, String content, Certainty certainty, Instant updatedAt,
            int qualityScore, String humanCorrection, Instant correctionAt, boolean humanVerified,
            Instant lastRetrievedAt, int quizUseCount, double certaintyScore,
            Status status, boolean reviewRequired, String prerequisiteSlugs
    ) {
        this(id, avatarId, slug, title, content, certainty, updatedAt,
                qualityScore, humanCorrection, correctionAt, humanVerified,
                lastRetrievedAt, quizUseCount, certaintyScore, status, reviewRequired,
                prerequisiteSlugs, false);
    }

    public static WikiPage create(String avatarId, String slug, String title, String content) {
        return new WikiPage(IdGenerator.newId(), avatarId, slug, title, content,
                Certainty.INFERRED, Instant.now(), 0, null, null, false,
                null, 0, 0.5, Status.ACTIVE, false, null);
    }

    public static WikiPage reconstitute(
            String id, String avatarId, String slug,
            String title, String content, Certainty certainty, Instant updatedAt
    ) {
        return new WikiPage(id, avatarId, slug, title, content, certainty, updatedAt,
                0, null, null, false,
                null, 0, 0.5, Status.ACTIVE, false, null);
    }

    public static WikiPage reconstitute(
            String id, String avatarId, String slug,
            String title, String content, Certainty certainty, Instant updatedAt,
            int qualityScore, String humanCorrection, Instant correctionAt, boolean humanVerified
    ) {
        return new WikiPage(id, avatarId, slug, title, content, certainty, updatedAt,
                qualityScore, humanCorrection, correctionAt, humanVerified,
                null, 0, 0.5, Status.ACTIVE, false, null);
    }

    public static WikiPage reconstitute(
            String id, String avatarId, String slug,
            String title, String content, Certainty certainty, Instant updatedAt,
            int qualityScore, String humanCorrection, Instant correctionAt, boolean humanVerified,
            Instant lastRetrievedAt, int quizUseCount, double certaintyScore,
            Status status, boolean reviewRequired, String prerequisiteSlugs
    ) {
        return new WikiPage(id, avatarId, slug, title, content, certainty, updatedAt,
                qualityScore, humanCorrection, correctionAt, humanVerified,
                lastRetrievedAt, quizUseCount, certaintyScore, status, reviewRequired,
                prerequisiteSlugs, false);
    }

    public static WikiPage reconstitute(
            String id, String avatarId, String slug,
            String title, String content, Certainty certainty, Instant updatedAt,
            int qualityScore, String humanCorrection, Instant correctionAt, boolean humanVerified,
            Instant lastRetrievedAt, int quizUseCount, double certaintyScore,
            Status status, boolean reviewRequired, String prerequisiteSlugs,
            boolean hasConflict
    ) {
        return new WikiPage(id, avatarId, slug, title, content, certainty, updatedAt,
                qualityScore, humanCorrection, correctionAt, humanVerified,
                lastRetrievedAt, quizUseCount, certaintyScore, status, reviewRequired,
                prerequisiteSlugs, hasConflict);
    }

    public void updateContent(String newTitle, String newContent, Certainty newCertainty) {
        this.title = newTitle;
        this.content = newContent;
        this.certainty = newCertainty;
        this.updatedAt = Instant.now();
    }

    public void applyHumanCorrection(String correction) {
        this.humanCorrection = correction;
        this.correctionAt = Instant.now();
        this.humanVerified = true;
        this.certainty = Certainty.VERIFIED;
        this.certaintyScore = 0.9;
        this.updatedAt = Instant.now();
    }

    public void setQualityScore(int score) {
        this.qualityScore = Math.max(0, Math.min(100, score));
    }

    /** Flags this page as having a content conflict with a previous version. */
    public void markConflict() {
        this.hasConflict = true;
    }

    /** Clears the conflict flag (e.g. after a human correction). */
    public void clearConflict() {
        this.hasConflict = false;
    }

    /** Nudges certainty within [0.1, 1.0]. Positive = reinforced, negative = shaken. */
    public void adjustCertaintyScore(double delta) {
        this.certaintyScore =
                Math.max(0.1, Math.min(1.0, this.certaintyScore + delta));
        this.updatedAt = Instant.now();
    }

    /** Flags (or clears) the page as needing student/parent review. */
    public void setReviewRequired(boolean value) {
        this.reviewRequired = value;
    }

    /** Sets the prerequisite slug list (comma-separated for the TEXT column). */
    public void setPrerequisiteSlugs(String slugs) {
        this.prerequisiteSlugs = slugs;
    }

    /** Updates lifecycle status (ACTIVE / ARCHIVED / REVIEW). */
    public void setStatus(Status status) {
        if (status != null) this.status = status;
    }

    public String getSummary() {
        if (content == null || content.isBlank()) return "";
        int end = content.indexOf('\n');
        String first = end > 0 ? content.substring(0, end) : content;
        return first.length() > 120 ? first.substring(0, 120) : first;
    }

    public String getId()                  { return id; }
    public String getAvatarId()            { return avatarId; }
    public String getSlug()                { return slug; }
    public String getTitle()               { return title; }
    public String getContent()             { return content; }
    public Certainty getCertainty()        { return certainty; }
    public Instant getUpdatedAt()          { return updatedAt; }
    public int getQualityScore()           { return qualityScore; }
    public String getHumanCorrection()     { return humanCorrection; }
    public Instant getCorrectionAt()       { return correctionAt; }
    public boolean isHumanVerified()       { return humanVerified; }
    public Instant getLastRetrievedAt()    { return lastRetrievedAt; }
    public int getQuizUseCount()           { return quizUseCount; }
    public double getCertaintyScore()      { return certaintyScore; }
    public Status getStatus()              { return status; }
    public boolean isReviewRequired()      { return reviewRequired; }
    public String getPrerequisiteSlugs()   { return prerequisiteSlugs; }
    public boolean isHasConflict()         { return hasConflict; }
}
