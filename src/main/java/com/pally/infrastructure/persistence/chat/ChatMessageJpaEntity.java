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

    @Column(name = "message_type", length = 20)
    private String messageType = "text";

    @Column(name = "source_wiki_slug", length = 200)
    private String sourceWikiSlug;

    @Column(name = "feedback_type", length = 20)
    private String feedbackType;

    @Column(name = "saved_to_brain")
    private boolean savedToBrain = false;

    @Column(name = "is_photo_message")
    private boolean isPhotoMessage = false;

    @Column(name = "cache_hit")
    private Boolean cacheHit;

    @Column(name = "cache_read_tokens")
    private Integer cacheReadTokens;

    @Column(name = "cache_write_tokens")
    private Integer cacheWriteTokens;

    @Column(name = "total_input_tokens")
    private Integer totalInputTokens;

    @Column(name = "total_output_tokens")
    private Integer totalOutputTokens;

    @Column(name = "model_used", length = 50)
    private String modelUsed;

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
        e.messageType = msg.getMessageType() != null ? msg.getMessageType() : "text";
        e.sourceWikiSlug = msg.getSourceWikiSlug();
        e.feedbackType = msg.getFeedbackType();
        e.savedToBrain = msg.isSavedToBrain();
        e.isPhotoMessage = msg.isPhotoMessage();
        e.createdAt = msg.getCreatedAt();
        e.harnessTrace = msg.getHarnessTrace();
        return e;
    }

    public ChatMessage toDomain() {
        return ChatMessage.reconstitute(
                id, avatarId, userId, role, content, sourceFile,
                messageType, sourceWikiSlug, feedbackType, savedToBrain, isPhotoMessage,
                createdAt, harnessTrace);
    }
}
