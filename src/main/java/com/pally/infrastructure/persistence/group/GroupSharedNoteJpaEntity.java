package com.pally.infrastructure.persistence.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "group_shared_notes")
@Getter
@Setter
@NoArgsConstructor
public class GroupSharedNoteJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "group_id", nullable = false, length = 36)
    private String groupId;

    @Column(name = "wiki_page_id", nullable = false, length = 36)
    private String wikiPageId;

    @Column(length = 255)
    private String title;

    @Column(name = "shared_by", nullable = false, length = 36)
    private String sharedBy;

    @Column(name = "shared_at", nullable = false)
    private Instant sharedAt;

    /// OK | WARNING | BLOCKED — set on share when relevance check runs.
    @Column(name = "relevance_status", nullable = false, length = 20)
    private String relevanceStatus = "OK";

    @Column(name = "relevance_score")
    private Float relevanceScore;

    @Column(name = "relevance_reason", columnDefinition = "TEXT")
    private String relevanceReason;
}
