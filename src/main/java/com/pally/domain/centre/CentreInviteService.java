package com.pally.domain.centre;

import com.pally.infrastructure.persistence.organization.CentreInviteTokenJpaEntity;
import com.pally.infrastructure.persistence.organization.CentreInviteTokenJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Invite-based centre provisioning.
 *
 * <p>Platform admin creates a token; the prospective centre owner
 * accepts it via a web link to register their org without needing
 * a public self-serve signup route.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentreInviteService {

    private static final int TOKEN_BYTES = 32;
    private static final int INVITE_VALID_DAYS = 7;

    private final CentreInviteTokenJpaRepository inviteRepo;
    private final OrganizationJpaRepository orgRepo;
    private final OrgStaffJpaRepository staffRepo;
    private final UserJpaRepository userRepo;

    // ── Admin: create invite ──────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> createInvite(String adminUserId, Map<String, Object> body) {
        String centreName = body == null ? null : (String) body.get("centreName");
        String contactEmail = body == null ? null : (String) body.get("contactEmail");
        if (centreName == null || centreName.isBlank()) {
            throw new BusinessException("centreName is required", 400);
        }
        if (contactEmail == null || contactEmail.isBlank()) {
            throw new BusinessException("contactEmail is required", 400);
        }

        String token = generateToken();
        CentreInviteTokenJpaEntity invite = new CentreInviteTokenJpaEntity();
        invite.setToken(token);
        invite.setCentreName(centreName.trim());
        invite.setContactEmail(contactEmail.trim().toLowerCase());
        invite.setCreatedBy(adminUserId);
        invite.setExpiresAt(Instant.now().plus(INVITE_VALID_DAYS, ChronoUnit.DAYS));
        inviteRepo.save(invite);

        log.info("[Invite] admin={} created invite for centre='{}' email={}",
                adminUserId, centreName, contactEmail);
        return Map.of(
                "token", token,
                "centreName", centreName,
                "contactEmail", contactEmail,
                "expiresAt", invite.getExpiresAt().toString()
        );
    }

    // ── Admin: list invites ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listInvites() {
        return inviteRepo.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDto)
                .toList();
    }

    // ── Public: look up invite info (for the accept-invite page) ─────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getInvite(String token) {
        CentreInviteTokenJpaEntity invite = requireValid(token);
        return Map.of(
                "centreName", invite.getCentreName(),
                "contactEmail", invite.getContactEmail(),
                "expiresAt", invite.getExpiresAt().toString()
        );
    }

    // ── Public: accept invite ─────────────────────────────────────────────────

    /**
     * Accepts the invite. Branches on role:
     * <ul>
     *   <li>OWNER — creates a new org and records the user as its owner (idempotent).</li>
     *   <li>STAFF — inserts an org_staff row linking the user to the existing org.</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> acceptInvite(String userId, Map<String, Object> body) {
        String token = body == null ? null : (String) body.get("token");
        if (token == null || token.isBlank()) {
            throw new BusinessException("token is required", 400);
        }
        CentreInviteTokenJpaEntity invite = requireValid(token);

        if ("STAFF".equals(invite.getRole())) {
            return acceptStaffInvite(userId, token, invite);
        }
        return acceptOwnerInvite(userId, token, invite);
    }

    private Map<String, Object> acceptOwnerInvite(
            String userId, String token, CentreInviteTokenJpaEntity invite) {
        // Idempotent for the same user.
        if (userId.equals(invite.getAcceptedBy()) && invite.getOrgId() != null) {
            OrganizationJpaEntity org = orgRepo.findById(invite.getOrgId())
                    .orElseThrow(() -> new BusinessException("Organisation not found", 404));
            return orgResponse(org, false);
        }

        orgRepo.findFirstByOwnerUserId(userId).ifPresent(existing -> {
            throw new BusinessException("You already own an organisation", 409);
        });

        userRepo.findById(userId).orElseThrow(
                () -> new BusinessException("User not found", 404));

        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId(IdGenerator.newId());
        org.setName(invite.getCentreName());
        org.setOwnerUserId(userId);
        org.setSeatLimit(30);
        org.setCreatedAt(Instant.now());
        orgRepo.save(org);

        invite.setAcceptedBy(userId);
        invite.setAcceptedAt(Instant.now());
        invite.setOrgId(org.getId());
        inviteRepo.save(invite);

        log.info("[Invite] OWNER token={} accepted by user={} → org={}", token, userId, org.getId());
        return orgResponse(org, true);
    }

    private Map<String, Object> acceptStaffInvite(
            String userId, String token, CentreInviteTokenJpaEntity invite) {
        String orgId = invite.getOrgId();
        if (orgId == null || orgId.isBlank()) {
            throw new BusinessException("Staff invite has no org assigned", 500);
        }
        OrganizationJpaEntity org = orgRepo.findById(orgId)
                .orElseThrow(() -> new BusinessException("Organisation not found", 404));

        // Idempotent: already attached.
        if (staffRepo.existsByOrgIdAndUserIdAndStatus(orgId, userId, OrgStaffJpaEntity.STATUS_ACTIVE)) {
            return orgResponse(org, false);
        }

        userRepo.findById(userId).orElseThrow(
                () -> new BusinessException("User not found", 404));

        OrgStaffJpaEntity staff = new OrgStaffJpaEntity();
        staff.setId(IdGenerator.newId());
        staff.setOrgId(orgId);
        staff.setUserId(userId);
        staffRepo.save(staff);

        invite.setAcceptedBy(userId);
        invite.setAcceptedAt(Instant.now());
        inviteRepo.save(invite);

        log.info("[Invite] STAFF token={} accepted by user={} → org={}", token, userId, orgId);
        return orgResponse(org, true);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private CentreInviteTokenJpaEntity requireValid(String token) {
        CentreInviteTokenJpaEntity invite = inviteRepo.findById(token)
                .orElseThrow(() -> new BusinessException("Invite not found", 404));
        if (Instant.now().isAfter(invite.getExpiresAt())) {
            throw new BusinessException("This invite has expired", 410);
        }
        if (invite.getAcceptedAt() != null) {
            throw new BusinessException("This invite has already been accepted", 409);
        }
        return invite;
    }

    private Map<String, Object> orgResponse(OrganizationJpaEntity org, boolean created) {
        Map<String, Object> m = new HashMap<>();
        m.put("orgId", org.getId());
        m.put("orgName", org.getName());
        m.put("created", created);
        return m;
    }

    private Map<String, Object> toDto(CentreInviteTokenJpaEntity invite) {
        Map<String, Object> m = new HashMap<>();
        m.put("token", invite.getToken());
        m.put("centreName", invite.getCentreName());
        m.put("contactEmail", invite.getContactEmail());
        m.put("role", invite.getRole());
        m.put("createdBy", invite.getCreatedBy());
        m.put("acceptedBy", invite.getAcceptedBy());
        m.put("orgId", invite.getOrgId());
        m.put("expiresAt", invite.getExpiresAt().toString());
        m.put("acceptedAt", invite.getAcceptedAt() == null ? null : invite.getAcceptedAt().toString());
        m.put("createdAt", invite.getCreatedAt().toString());
        return m;
    }

    private static String generateToken() {
        SecureRandom rng = new SecureRandom();
        byte[] bytes = new byte[TOKEN_BYTES];
        rng.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(TOKEN_BYTES * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
