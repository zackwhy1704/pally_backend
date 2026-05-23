package com.pally.domain.knowledge;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;

/**
 * Domain entity for a single markdown wiki page belonging to an avatar.
 */
public final class WikiPage {

    public enum Certainty { INFERRED, VERIFIED, UNCERTAIN }

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

    private WikiPage(
            String id, String avatarId, String slug,
            String title, String content, Certainty certainty, Instant updatedAt,
            int qualityScore, String humanCorrection, Instant correctionAt, boolean humanVerified
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
    }

    public static WikiPage create(String avatarId, String slug, String title, String content) {
        return new WikiPage(IdGenerator.newId(), avatarId, slug, title, content,
                Certainty.INFERRED, Instant.now(), 0, null, null, false);
    }

    public static WikiPage reconstitute(
            String id, String avatarId, String slug,
            String title, String content, Certainty certainty, Instant updatedAt
    ) {
        return new WikiPage(id, avatarId, slug, title, content, certainty, updatedAt,
                0, null, null, false);
    }

    public static WikiPage reconstitute(
            String id, String avatarId, String slug,
            String title, String content, Certainty certainty, Instant updatedAt,
            int qualityScore, String humanCorrection, Instant correctionAt, boolean humanVerified
    ) {
        return new WikiPage(id, avatarId, slug, title, content, certainty, updatedAt,
                qualityScore, humanCorrection, correctionAt, humanVerified);
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
        this.updatedAt = Instant.now();
    }

    public void setQualityScore(int score) {
        this.qualityScore = Math.max(0, Math.min(100, score));
    }

    public String getId()               { return id; }
    public String getAvatarId()         { return avatarId; }
    public String getSlug()             { return slug; }
    public String getTitle()            { return title; }
    public String getContent()          { return content; }
    public Certainty getCertainty()     { return certainty; }
    public Instant getUpdatedAt()       { return updatedAt; }
    public int getQualityScore()        { return qualityScore; }
    public String getHumanCorrection()  { return humanCorrection; }
    public Instant getCorrectionAt()    { return correctionAt; }
    public boolean isHumanVerified()    { return humanVerified; }
}
