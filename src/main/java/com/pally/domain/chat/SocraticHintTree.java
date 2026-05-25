package com.pally.domain.chat;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;
import java.util.List;

/**
 * Pre-compiled Socratic hint tree for a single wiki page.
 * Generated at wiki compile time; used by SendMessageUseCase to add
 * guided-question steps to Block 4 without hitting the LLM on every turn.
 */
public final class SocraticHintTree {

    public record HintStep(int stepNumber, String guidingQuestion, String keyword) {}

    private final String id;
    private final String avatarId;
    private final String wikiSlug;
    private final List<String> topicKeywords;
    private final List<HintStep> hints;
    private final Instant createdAt;

    private SocraticHintTree(
            String id, String avatarId, String wikiSlug,
            List<String> topicKeywords, List<HintStep> hints, Instant createdAt) {
        this.id = id;
        this.avatarId = avatarId;
        this.wikiSlug = wikiSlug;
        this.topicKeywords = topicKeywords;
        this.hints = hints;
        this.createdAt = createdAt;
    }

    public static SocraticHintTree create(
            String avatarId, String wikiSlug,
            List<String> topicKeywords, List<HintStep> hints) {
        return new SocraticHintTree(
                IdGenerator.newId(), avatarId, wikiSlug,
                topicKeywords, hints, Instant.now());
    }

    public static SocraticHintTree reconstitute(
            String id, String avatarId, String wikiSlug,
            List<String> topicKeywords, List<HintStep> hints, Instant createdAt) {
        return new SocraticHintTree(id, avatarId, wikiSlug, topicKeywords, hints, createdAt);
    }

    public String getId()                   { return id; }
    public String getAvatarId()             { return avatarId; }
    public String getWikiSlug()             { return wikiSlug; }
    public List<String> getTopicKeywords()  { return topicKeywords; }
    public List<HintStep> getHints()        { return hints; }
    public Instant getCreatedAt()           { return createdAt; }
}
