package com.pally.infrastructure.persistence.safety;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "chat_safety_flags")
@Getter @Setter @NoArgsConstructor
public class ChatSafetyFlagJpaEntity {

    // Categories
    public static final String CAT_SELF_HARM     = "SELF_HARM";
    public static final String CAT_VIOLENCE      = "VIOLENCE";
    public static final String CAT_SEXUAL        = "SEXUAL";
    public static final String CAT_BULLYING      = "BULLYING";
    public static final String CAT_PERSONAL_DATA = "PERSONAL_DATA";
    public static final String CAT_OFF_TOPIC     = "OFF_TOPIC";

    // Severity
    public static final String SEV_HIGH   = "HIGH";
    public static final String SEV_MEDIUM = "MEDIUM";
    public static final String SEV_LOW    = "LOW";

    @Id @Column(length = 36)
    private String id;

    @Column(name = "child_user_id", nullable = false, length = 36)
    private String childUserId;

    @Column(name = "avatar_id", length = 36)
    private String avatarId;

    @Column(name = "message_id", length = 36)
    private String messageId;

    @Column(name = "category", nullable = false, length = 40)
    private String category;

    @Column(name = "severity", nullable = false, length = 10)
    private String severity;

    @Column(name = "snippet", columnDefinition = "TEXT")
    private String snippet;

    @Column(name = "source", nullable = false, length = 10)
    private String source = "INPUT"; // INPUT | OUTPUT

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
