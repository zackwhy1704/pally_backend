package com.pally.infrastructure.persistence.report;

import com.pally.domain.report.ContentReportReason;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "content_reports")
@Getter
@Setter
@NoArgsConstructor
public class ContentReportJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ContentReportReason reason;

    @Column(columnDefinition = "text")
    private String comment;

    @Column(name = "message_text", nullable = false, columnDefinition = "text")
    private String messageText;

    @Column(name = "client_message_id", length = 64)
    private String clientMessageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
