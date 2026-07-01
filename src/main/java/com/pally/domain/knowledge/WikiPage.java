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
    // Trusted-adult review provenance. verificationSource is one of
    // CORRECTION (human in-app edit) / ADULT_REVIEW (tokenized reviewer
    // approval); verifiedBy is the reviewer's display name when known.
    // Both stay null on plain AI-inferred pages.
    private String verificationSource;
    private String verifiedBy;
    // Short human-readable note explaining WHY this page conflicts. Stays null
    // until the (later-wave) conflict-explanation extraction populates it.
    private String conflictNote;
    // Contextual chunk summary (1-2 sentences: "This page covers X within
    // Subject/Topic Y") emitted by the compiler and PREPENDED to the page's
    // retrieval/grounding text. The single highest-impact cheap retrieval lift:
    // it disambiguates a page for the model even when the query is terse.
    private String context;

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
        // If a human has verified this page, never downgrade the certainty or
        // overwrite their correction with AI-synthesised content.
        if (!humanVerified) {
            this.content = newContent;
            this.certainty = newCertainty;
        }
        this.updatedAt = Instant.now();
    }

    /// A teacher's fact-conflict resolution: set the canonical content and clear the
    /// conflict flag. The {@code wiki_conflict} RESOLVED row is the durable lock that
    /// stops a later recompile from silently overwriting this (Part A).
    public void applyResolution(String canonicalContent) {
        this.content = canonicalContent;
        this.hasConflict = false;
        this.conflictNote = null;
        this.updatedAt = Instant.now();
    }

    public void applyHumanCorrection(String correction) {
        this.humanCorrection = correction;
        this.correctionAt = Instant.now();
        this.humanVerified = true;
        this.certainty = Certainty.VERIFIED;
        this.certaintyScore = 0.9;
        // A human in-app edit is a CORRECTION-sourced verification (matches the
        // V<next> backfill of existing humanCorrection-VERIFIED pages).
        this.verificationSource = "CORRECTION";
        // A verified page is, by definition, no longer in dispute — clear
        // both the harness review flag and any prior content-conflict flag
        // so the brain map / parent dashboard stop nagging about it.
        this.reviewRequired = false;
        clearConflict();
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

    /**
     * Marks this page as APPROVED by a trusted adult through the public
     * tokenized review flow: certainty → VERIFIED, source ADULT_REVIEW,
     * and records the reviewer name. Clears any prior conflict flag.
     */
    public void markAdultReviewApproved(String reviewerName) {
        this.certainty = Certainty.VERIFIED;
        this.verificationSource = "ADULT_REVIEW";
        this.verifiedBy = (reviewerName != null && !reviewerName.isBlank())
                ? reviewerName : "a reviewer";
        clearConflict();
        this.updatedAt = Instant.now();
    }

    /**
     * Marks this page as FLAGGED by a trusted adult: certainty → UNCERTAIN.
     * The flag note is stored on the review request, not the page.
     */
    public void markAdultReviewFlagged() {
        this.certainty = Certainty.UNCERTAIN;
        this.updatedAt = Instant.now();
    }

    /** Sets the verification source (CORRECTION / ADULT_REVIEW), null-tolerant. */
    public void setVerificationSource(String source) {
        this.verificationSource = source;
    }

    /** Sets the reviewer/verifier display name, null-tolerant. */
    public void setVerifiedBy(String verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    /** Sets the human-readable conflict explanation note, null-tolerant. */
    public void setConflictNote(String conflictNote) {
        this.conflictNote = conflictNote;
    }

    /** Sets the contextual chunk summary, null-tolerant. */
    public void setContext(String context) {
        this.context = context;
    }

    /**
     * The page text as fed to the model for grounding/retrieval: the contextual
     * summary (when present) prepended to the content, so a terse query still
     * lands the right page. Falls back to plain content when no context exists.
     */
    public String groundingText() {
        String body = humanCorrection != null && !humanCorrection.isBlank()
                ? humanCorrection : content;
        if (context == null || context.isBlank()) return body == null ? "" : body;
        return "_" + context.trim() + "_\n" + (body == null ? "" : body);
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
    public String getVerificationSource()  { return verificationSource; }
    public String getVerifiedBy()          { return verifiedBy; }
    public String getConflictNote()        { return conflictNote; }
    public String getContext()             { return context; }
}
