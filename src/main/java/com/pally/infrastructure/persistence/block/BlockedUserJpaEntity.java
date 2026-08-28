package com.pally.infrastructure.persistence.block;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA row for {@code blocked_users}. See V135 for the semantics. */
@Entity
@Table(name = "blocked_users")
public class BlockedUserJpaEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** The user who initiated the block — the one who stops seeing content. */
    @Column(name = "blocker_user_id", nullable = false, length = 36)
    private String blockerUserId;

    /** The user whose content is hidden FROM the blocker. */
    @Column(name = "blocked_user_id", nullable = false, length = 36)
    private String blockedUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getBlockerUserId() { return blockerUserId; }
    public void setBlockerUserId(String v) { this.blockerUserId = v; }

    public String getBlockedUserId() { return blockedUserId; }
    public void setBlockedUserId(String v) { this.blockedUserId = v; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
