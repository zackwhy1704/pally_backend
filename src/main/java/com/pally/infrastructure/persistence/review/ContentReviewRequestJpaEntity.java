package com.pally.infrastructure.persistence.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code content_review_request} table (V10). A single-use
 * tokenized request for a trusted adult to review one wiki page.
 *
 * <p>Lifecycle: PENDING → APPROVED | FLAGGED | EXPIRED | REVOKED.
 */
@Entity
@Table(name = "content_review_request")
public class ContentReviewRequestJpaEntity {

    public enum Status { PENDING, APPROVED, FLAGGED, EXPIRED, REVOKED }

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 64, unique = true)
    private String token;

    @Column(name = "wiki_page_id", nullable = false, length = 36)
    private String wikiPageId;

    @Column(name = "requester_id", nullable = false, length = 36)
    private String requesterId;

    @Column(nullable = false, length = 12)
    private String status = Status.PENDING.name();

    @Column(name = "reviewer_name", length = 80)
    private String reviewerName;

    @Column(name = "flag_note", length = 500)
    private String flagNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected ContentReviewRequestJpaEntity() {}

    public ContentReviewRequestJpaEntity(String id, String token, String wikiPageId,
                                         String requesterId, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.token = token;
        this.wikiPageId = wikiPageId;
        this.requesterId = requesterId;
        this.status = Status.PENDING.name();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isPending() {
        return Status.PENDING.name().equals(status);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    public String getId() { return id; }
    public String getToken() { return token; }
    public String getWikiPageId() { return wikiPageId; }
    public String getRequesterId() { return requesterId; }
    public String getStatus() { return status; }
    public String getReviewerName() { return reviewerName; }
    public String getFlagNote() { return flagNote; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public void setStatus(String status) { this.status = status; }
    public void setStatus(Status status) { this.status = status.name(); }
    public void setReviewerName(String reviewerName) { this.reviewerName = reviewerName; }
    public void setFlagNote(String flagNote) { this.flagNote = flagNote; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
}
