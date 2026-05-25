package com.pally.infrastructure.persistence.chat;

import com.pally.domain.chat.ChatMessage;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessageJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChatMessage.Role role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "source_file")
    private String sourceFile;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "harness_trace", columnDefinition = "TEXT")
    private String harnessTrace;

    public static ChatMessageJpaEntity fromDomain(ChatMessage msg) {
        ChatMessageJpaEntity e = new ChatMessageJpaEntity();
        e.id = msg.getId();
        e.avatarId = msg.getAvatarId();
        e.userId = msg.getUserId();
        e.role = msg.getRole();
        e.content = msg.getContent();
        e.sourceFile = msg.getSourceFile();
        e.createdAt = msg.getCreatedAt();
        e.harnessTrace = msg.getHarnessTrace();
        return e;
    }

    public ChatMessage toDomain() {
        return ChatMessage.reconstitute(id, avatarId, userId, role, content, sourceFile, createdAt, harnessTrace);
    }
}
