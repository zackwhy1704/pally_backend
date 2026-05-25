package com.pally.domain.chat;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;

public final class ChatMessage {

    public enum Role { USER, ASSISTANT }

    private final String id;
    private final String avatarId;
    private final String userId;
    private final Role role;
    private final String content;
    private final String sourceFile;
    private final String messageType;
    private final String sourceWikiSlug;
    private final String feedbackType;
    private final boolean savedToBrain;
    private final boolean photoMessage;
    private final Instant createdAt;
    private final String harnessTrace;

    private ChatMessage(
            String id, String avatarId, String userId,
            Role role, String content, String sourceFile,
            String messageType, String sourceWikiSlug,
            String feedbackType, boolean savedToBrain, boolean photoMessage,
            Instant createdAt, String harnessTrace
    ) {
        this.id = id;
        this.avatarId = avatarId;
        this.userId = userId;
        this.role = role;
        this.content = content;
        this.sourceFile = sourceFile;
        this.messageType = messageType;
        this.sourceWikiSlug = sourceWikiSlug;
        this.feedbackType = feedbackType;
        this.savedToBrain = savedToBrain;
        this.photoMessage = photoMessage;
        this.createdAt = createdAt;
        this.harnessTrace = harnessTrace;
    }

    public static ChatMessage create(
            String avatarId, String userId, Role role, String content, String sourceFile
    ) {
        return new ChatMessage(IdGenerator.newId(), avatarId, userId, role, content,
                sourceFile, "text", null, null, false, false, Instant.now(), null);
    }

    public static ChatMessage createWithTrace(
            String avatarId, String userId, Role role, String content,
            String sourceFile, String harnessTrace
    ) {
        return new ChatMessage(IdGenerator.newId(), avatarId, userId, role, content,
                sourceFile, "text", null, null, false, false, Instant.now(), harnessTrace);
    }

    public static ChatMessage reconstitute(
            String id, String avatarId, String userId,
            Role role, String content, String sourceFile, Instant createdAt
    ) {
        return new ChatMessage(id, avatarId, userId, role, content,
                sourceFile, "text", null, null, false, false, createdAt, null);
    }

    public static ChatMessage reconstitute(
            String id, String avatarId, String userId,
            Role role, String content, String sourceFile, Instant createdAt,
            String harnessTrace
    ) {
        return new ChatMessage(id, avatarId, userId, role, content,
                sourceFile, "text", null, null, false, false, createdAt, harnessTrace);
    }

    public static ChatMessage reconstitute(
            String id, String avatarId, String userId,
            Role role, String content, String sourceFile,
            String messageType, String sourceWikiSlug,
            String feedbackType, boolean savedToBrain, boolean photoMessage,
            Instant createdAt, String harnessTrace
    ) {
        return new ChatMessage(id, avatarId, userId, role, content,
                sourceFile, messageType, sourceWikiSlug,
                feedbackType, savedToBrain, photoMessage, createdAt, harnessTrace);
    }

    public String getId()              { return id; }
    public String getAvatarId()        { return avatarId; }
    public String getUserId()          { return userId; }
    public Role getRole()              { return role; }
    public String getContent()         { return content; }
    public String getSourceFile()      { return sourceFile; }
    public String getMessageType()     { return messageType; }
    public String getSourceWikiSlug()  { return sourceWikiSlug; }
    public String getFeedbackType()    { return feedbackType; }
    public boolean isSavedToBrain()    { return savedToBrain; }
    public boolean isPhotoMessage()    { return photoMessage; }
    public Instant getCreatedAt()      { return createdAt; }
    public String getHarnessTrace()    { return harnessTrace; }
}
