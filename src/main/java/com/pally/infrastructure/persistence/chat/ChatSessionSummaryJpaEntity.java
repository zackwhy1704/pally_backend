package com.pally.infrastructure.persistence.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Rolling per-avatar memory of past chat sessions. The summary feeds back
 * into the tutor's system prompt on subsequent turns so the avatar grows
 * with the child over time.
 */
@Entity
@Table(name = "chat_session_summary")
@Getter
@Setter
@NoArgsConstructor
public class ChatSessionSummaryJpaEntity {

    @Id
    @Column(name = "avatar_id", length = 36, nullable = false)
    private String avatarId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
