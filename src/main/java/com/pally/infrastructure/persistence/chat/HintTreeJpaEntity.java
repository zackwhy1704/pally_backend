package com.pally.infrastructure.persistence.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.chat.SocraticHintTree;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "hint_trees")
@Getter
@Setter
@NoArgsConstructor
public class HintTreeJpaEntity {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(name = "wiki_slug", nullable = false, length = 200)
    private String wikiSlug;

    @Column(name = "topic_keywords", nullable = false, columnDefinition = "text")
    private String topicKeywords;

    @Column(name = "hints_json", nullable = false, columnDefinition = "text")
    private String hintsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static HintTreeJpaEntity fromDomain(SocraticHintTree tree) {
        HintTreeJpaEntity entity = new HintTreeJpaEntity();
        entity.id = tree.getId();
        entity.avatarId = tree.getAvatarId();
        entity.wikiSlug = tree.getWikiSlug();
        entity.topicKeywords = String.join(",", tree.getTopicKeywords());
        try {
            entity.hintsJson = MAPPER.writeValueAsString(tree.getHints());
        } catch (Exception e) {
            entity.hintsJson = "[]";
        }
        entity.createdAt = tree.getCreatedAt();
        return entity;
    }

    public SocraticHintTree toDomain() {
        List<String> keywords = topicKeywords.isBlank()
                ? List.of()
                : Arrays.asList(topicKeywords.split(","));

        List<SocraticHintTree.HintStep> hints;
        try {
            hints = MAPPER.readValue(hintsJson, new TypeReference<List<SocraticHintTree.HintStep>>() {});
        } catch (Exception e) {
            hints = List.of();
        }

        return SocraticHintTree.reconstitute(id, avatarId, wikiSlug, keywords, hints, createdAt);
    }
}
