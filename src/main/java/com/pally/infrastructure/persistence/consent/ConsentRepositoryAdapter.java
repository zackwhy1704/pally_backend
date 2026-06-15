package com.pally.infrastructure.persistence.consent;

import com.pally.domain.consent.ConsentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * JPA adapter that satisfies the {@link ConsentRepository} domain port.
 * Maps between JPA entities and domain records.
 */
@Component
@RequiredArgsConstructor
public class ConsentRepositoryAdapter implements ConsentRepository {

    private final ConsentRecordJpaRepository  recordJpa;
    private final ConsentRequestJpaRepository requestJpa;

    // ── Consent records ────────────────────────────────────────────────────

    @Override
    public ConsentRecord saveRecord(ConsentRecord r) {
        ConsentRecordJpaEntity e = new ConsentRecordJpaEntity();
        e.setId(r.id());
        e.setUserId(r.userId());
        e.setConsenter(r.consenter());
        e.setMethod(r.method());
        e.setPurposes(r.purposes());
        e.setPolicyVersion(r.policyVersion());
        e.setCreatedAt(r.createdAt());
        e.setChildId(r.childId());
        e.setParentId(r.parentId());
        recordJpa.save(e);
        return r;
    }

    @Override
    public boolean hasConsentForPurpose(String userId, String purposeSubstring) {
        return recordJpa.findAll().stream()
                .anyMatch(r -> userId.equals(r.getUserId())
                        && r.getPurposes() != null
                        && r.getPurposes().contains(purposeSubstring));
    }

    // ── Consent requests ────────────────────────────────────────────────────

    @Override
    public ConsentRequest saveRequest(ConsentRequest r) {
        ConsentRequestJpaEntity e = new ConsentRequestJpaEntity();
        e.setId(r.id());
        e.setChildUserId(r.childUserId());
        e.setParentEmail(r.parentEmail());
        e.setToken(r.token());
        e.setStatus(r.status());
        e.setCreatedAt(r.createdAt());
        e.setExpiresAt(r.expiresAt());
        e.setApprovedAt(r.approvedAt());
        requestJpa.save(e);
        return r;
    }

    @Override
    public Optional<ConsentRequest> findRequestByToken(String token) {
        return requestJpa.findByToken(token).map(this::toDomain);
    }

    @Override
    public Optional<ConsentRequest> findLatestRequestByChildUserIdAndStatus(
            String childUserId, String status) {
        return requestJpa.findFirstByChildUserIdAndStatusOrderByCreatedAtDesc(
                childUserId, status).map(this::toDomain);
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private ConsentRequest toDomain(ConsentRequestJpaEntity e) {
        return new ConsentRequest(
                e.getId(), e.getChildUserId(), e.getParentEmail(),
                e.getToken(), e.getStatus(),
                e.getCreatedAt(), e.getExpiresAt(), e.getApprovedAt()
        );
    }
}
