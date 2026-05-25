package com.pally.infrastructure.persistence.chat;

import com.pally.domain.chat.ChatSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "chat_sessions")
@Getter
@Setter
@NoArgsConstructor
public class ChatSessionJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(name = "current_topic", length = 200)
    private String currentTopic;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "escape_fired", nullable = false)
    private boolean escapeFired;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static ChatSessionJpaEntity fromDomain(ChatSession session) {
        ChatSessionJpaEntity entity = new ChatSessionJpaEntity();
        entity.id = session.getId();
        entity.avatarId = session.getAvatarId();
        entity.currentTopic = session.getCurrentTopic();
        entity.attemptCount = session.getAttemptCount();
        entity.escapeFired = session.isEscapeFired();
        entity.sessionDate = session.getSessionDate();
        entity.updatedAt = session.getUpdatedAt();
        return entity;
    }

    public ChatSession toDomain() {
        return ChatSession.reconstitute(
                id, avatarId, currentTopic, attemptCount, escapeFired, sessionDate, updatedAt);
    }
}
