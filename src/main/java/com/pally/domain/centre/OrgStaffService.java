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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Staff membership for multi-teacher centres: invite, list, remove.
 * Only org owners may call mutating operations (enforced by CentreAccessService
 * in the controller before delegation here).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrgStaffService {

    private static final int TOKEN_BYTES      = 32;
    private static final int INVITE_VALID_DAYS = 7;

    private final CentreAccessService accessService;
    private final OrgStaffJpaRepository staffRepo;
    private final OrganizationJpaRepository orgRepo;
    private final UserJpaRepository userRepo;
    private final CentreInviteTokenJpaRepository inviteRepo;

    // ── List staff (owner or staff) ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listStaff(String callerId, String orgId) {
        OrganizationJpaEntity org = accessService.ensureStaff(callerId, orgId);
        List<Map<String, Object>> out = new ArrayList<>();

        // Owner row first.
        userRepo.findById(org.getOwnerUserId()).ifPresent(owner -> {
            Map<String, Object> row = new HashMap<>();
            row.put("userId", owner.getId());
            row.put("displayName", owner.getDisplayName() != null ? owner.getDisplayName() : "");
            row.put("email", owner.getEmail() != null ? owner.getEmail() : "");
            row.put("role", "OWNER");
            row.put("status", "ACTIVE");
            out.add(row);
        });

        for (OrgStaffJpaEntity s : staffRepo.findByOrgIdAndStatus(orgId, OrgStaffJpaEntity.STATUS_ACTIVE)) {
            userRepo.findById(s.getUserId()).ifPresent(u -> {
                Map<String, Object> row = new HashMap<>();
                row.put("userId", u.getId());
                row.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : "");
                row.put("email", u.getEmail() != null ? u.getEmail() : "");
                row.put("role", OrgStaffJpaEntity.ROLE_STAFF);
                row.put("status", s.getStatus());
                out.add(row);
            });
        }
        return out;
    }

    // ── Invite staff (owner-only) ─────────────────────────────────────────────

    /**
     * Creates a STAFF invite token for the given email.
     * If the email already belongs to an active user, attaches them immediately
     * (no registration step needed) and returns without a token link.
     */
    @Transactional
    public Map<String, Object> inviteStaff(String callerId, String orgId, String email) {
        accessService.ensureOwner(callerId, orgId);
        if (email == null || email.isBlank()) {
            throw new BusinessException("email is required", 400);
        }
        OrganizationJpaEntity org = orgRepo.findById(orgId)
                .orElseThrow(() -> new BusinessException("Organization not found", 404));
        String normalizedEmail = email.trim().toLowerCase();

        // Fast-path: existing user → attach immediately.
        var existingUser = userRepo.findByEmail(normalizedEmail);
        if (existingUser.isPresent()) {
            String staffUserId = existingUser.get().getId();
            if (staffRepo.existsByOrgIdAndUserIdAndStatus(orgId, staffUserId, OrgStaffJpaEntity.STATUS_ACTIVE)) {
                throw new BusinessException("User is already an active staff member", 409);
            }
            // Re-activate if previously removed.
            staffRepo.findByOrgIdAndUserId(orgId, staffUserId).ifPresentOrElse(s -> {
                s.setStatus(OrgStaffJpaEntity.STATUS_ACTIVE);
                s.setRemovedAt(null);
                staffRepo.save(s);
            }, () -> {
                OrgStaffJpaEntity s = new OrgStaffJpaEntity();
                s.setId(IdGenerator.newId());
                s.setOrgId(orgId);
                s.setUserId(staffUserId);
                staffRepo.save(s);
            });
            log.info("[Staff] direct-attach userId={} to org={}", staffUserId, orgId);
            Map<String, Object> out = new HashMap<>();
            out.put("attached", true);
            out.put("userId", staffUserId);
            out.put("email", normalizedEmail);
            return out;
        }

        // Slow-path: unknown email → issue invite token.
        String token = generateToken();
        CentreInviteTokenJpaEntity invite = new CentreInviteTokenJpaEntity();
        invite.setToken(token);
        invite.setCentreName(org.getName());
        invite.setContactEmail(normalizedEmail);
        invite.setCreatedBy(callerId);
        invite.setRole("STAFF");
        invite.setOrgId(orgId);
        invite.setExpiresAt(Instant.now().plus(INVITE_VALID_DAYS, ChronoUnit.DAYS));
        inviteRepo.save(invite);

        log.info("[Staff] invite token={} org={} email={}", token, orgId, normalizedEmail);
        Map<String, Object> out = new HashMap<>();
        out.put("attached", false);
        out.put("token", token);
        out.put("email", normalizedEmail);
        out.put("expiresAt", invite.getExpiresAt().toString());
        return out;
    }

    // ── Remove staff (owner-only) ─────────────────────────────────────────────

    @Transactional
    public void removeStaff(String callerId, String orgId, String staffUserId) {
        accessService.ensureOwner(callerId, orgId);
        if (callerId.equals(staffUserId)) {
            throw new BusinessException("You cannot remove yourself as owner", 400);
        }
        OrgStaffJpaEntity staff = staffRepo.findByOrgIdAndUserId(orgId, staffUserId)
                .orElseThrow(() -> new BusinessException("Staff member not found in this org", 404));
        if (OrgStaffJpaEntity.STATUS_REMOVED.equals(staff.getStatus())) {
            return; // idempotent
        }
        staff.setStatus(OrgStaffJpaEntity.STATUS_REMOVED);
        staff.setRemovedAt(Instant.now());
        staffRepo.save(staff);
        log.info("[Staff] removed userId={} from org={}", staffUserId, orgId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String generateToken() {
        SecureRandom rng = new SecureRandom();
        byte[] bytes = new byte[TOKEN_BYTES];
        rng.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(TOKEN_BYTES * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
