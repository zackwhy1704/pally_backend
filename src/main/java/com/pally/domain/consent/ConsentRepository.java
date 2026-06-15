package com.pally.domain.consent;

import java.time.Instant;
import java.util.Optional;

/**
 * Domain port for consent record and request persistence.
 * Implementations live in {@code infrastructure/persistence/consent}.
 */
public interface ConsentRepository {

    // ── Consent records ────────────────────────────────────────────────────

    /** Persists a consent record and returns it. */
    ConsentRecord saveRecord(ConsentRecord record);

    /**
     * Returns true if the user has any consent record whose purposes
     * contains the given reason string (e.g. {@code "AI_DATA_TRANSFER"}).
     */
    boolean hasConsentForPurpose(String userId, String purposeSubstring);

    // ── Consent requests ────────────────────────────────────────────────────

    /** Persists a consent request and returns it. */
    ConsentRequest saveRequest(ConsentRequest request);

    /** Looks up a consent request by its approval token. */
    Optional<ConsentRequest> findRequestByToken(String token);

    /**
     * Returns the most recent consent request for the child with the
     * given status, or empty if none exists.
     */
    Optional<ConsentRequest> findLatestRequestByChildUserIdAndStatus(
            String childUserId, String status);

    // ── Domain records ─────────────────────────────────────────────────────

    record ConsentRecord(
            String id,
            String userId,
            String consenter,
            String method,
            String purposes,
            String policyVersion,
            Instant createdAt,
            String childId,
            String parentId
    ) {}

    record ConsentRequest(
            String id,
            String childUserId,
            String parentEmail,
            String token,
            String status,
            Instant createdAt,
            Instant expiresAt,
            Instant approvedAt
    ) {
        public static final String STATUS_PENDING  = "PENDING";
        public static final String STATUS_APPROVED = "APPROVED";
        public static final String STATUS_DECLINED = "DECLINED";
        public static final String STATUS_EXPIRED  = "EXPIRED";
    }
}
