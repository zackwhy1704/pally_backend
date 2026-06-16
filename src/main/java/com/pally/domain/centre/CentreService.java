package com.pally.domain.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.organization.ClassEnrollmentService;
import com.pally.shared.AdminSecretGuard;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.organization.CentreEnrollCodeJpaEntity;
import com.pally.infrastructure.persistence.organization.CentreEnrollCodeJpaRepository;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Application service for centre (B2B) operations — owns all logic + repo access
 * so {@link CentreController} stays a thin HTTP delegator. Returns the exact
 * response shapes the controller wraps (Maps, and the raw CSV string for export),
 * so the HTTP contract is identical. Owner gating runs inside each admin method
 * via {@link CentreAccessService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentreService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LEN = 6;

    private final AdminSecretGuard adminSecretGuard;
    private final CentreAccessService accessService;
    private final OrganizationJpaRepository orgRepo;
    private final CentreEnrollCodeJpaRepository codeRepo;
    private final OrgClassJpaRepository classRepo;
    private final ClassMembershipJpaRepository membershipRepo;
    private final ClassEnrollmentService classEnrollmentService;
    private final UserJpaRepository userRepo;
    private final QuizQuestionResultJpaRepository quizResultRepo;
    private final AvatarJpaRepository avatarJpaRepository;

    // ── Student-side: redeem an enrollment code ───────────────────────

    @Transactional
    public Map<String, Object> redeem(String userId, Map<String, String> body) {
        String raw = body == null ? null : body.get("code");
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("code is required", 400);
        }
        String code = raw.trim().toUpperCase();
        CentreEnrollCodeJpaEntity row = codeRepo.findById(code).orElseThrow(
                () -> new BusinessException("That code doesn't exist", 404));
        if (row.getExpiresAt() != null && Instant.now().isAfter(row.getExpiresAt())) {
            throw new BusinessException("That code has expired", 410);
        }
        // Atomic check-and-increment so the seat cap holds under concurrent redeems.
        int updated = codeRepo.incrementUses(code);
        if (updated == 0) {
            throw new BusinessException("This code has reached its seat limit", 409);
        }
        UserJpaEntity user = userRepo.findById(userId).orElseThrow(
                () -> new BusinessException("User not found", 404));
        user.setCentreId(row.getOrganizationId());
        user.setCohortLabel(row.getCohortLabel());
        userRepo.save(user);
        log.info("[Centre] user={} joined org={} cohort={}",
                userId, row.getOrganizationId(), row.getCohortLabel());
        return Map.of(
                "organizationId", row.getOrganizationId(),
                "cohortLabel", row.getCohortLabel());
    }

    // ── Student-side: join a class directly by its class code ─────────

    @Transactional
    public Map<String, Object> redeemClassCode(String userId, Map<String, String> body) {
        String raw = body == null ? null : body.get("code");
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("code is required", 400);
        }
        String code = raw.trim().toUpperCase();
        OrgClassJpaEntity cls = classRepo.findByJoinCode(code).orElseThrow(
                () -> new BusinessException("That class code doesn't exist", 404));

        // The class code IS the centre invite — joining a class joins its centre.
        UserJpaEntity user = userRepo.findById(userId).orElseThrow(
                () -> new BusinessException("User not found", 404));
        user.setCentreId(cls.getOrganizationId());
        if (user.getCohortLabel() == null || user.getCohortLabel().isBlank()) {
            user.setCohortLabel(cls.getName());
        }
        userRepo.save(user);

        String avatarId = classEnrollmentService.enroll(cls, userId);
        log.info("[Centre] user={} joined class={} org={} avatar={}",
                userId, cls.getId(), cls.getOrganizationId(), avatarId);

        Map<String, Object> out = new HashMap<>();
        out.put("classId", cls.getId());
        out.put("className", cls.getName());
        out.put("organizationId", cls.getOrganizationId());
        out.put("avatarId", avatarId);
        return out;
    }

    // ── Student-side: leave a class ───────────────────────────────────

    @Transactional
    public Map<String, Object> leaveClass(String userId, Map<String, String> body) {
        String classId = body == null ? null : body.get("classId");
        if (classId == null || classId.isBlank()) {
            throw new BusinessException("classId is required", 400);
        }
        OrgClassJpaEntity cls = classRepo.findById(classId).orElseThrow(
                () -> new BusinessException("That class doesn't exist", 404));

        classEnrollmentService.leave(cls, userId);

        // Clear centre membership only if no other active class in the SAME centre.
        boolean stillInCentre = membershipRepo.findByUserId(userId).stream()
                .filter(m -> ClassMembershipJpaEntity.STATUS_ACTIVE.equals(m.getStatus()))
                .map(m -> classRepo.findById(m.getClassId()).orElse(null))
                .filter(c -> c != null)
                .anyMatch(c -> cls.getOrganizationId().equals(c.getOrganizationId()));
        if (!stillInCentre) {
            UserJpaEntity user = userRepo.findById(userId).orElse(null);
            if (user != null && cls.getOrganizationId().equals(user.getCentreId())) {
                user.setCentreId(null);
                user.setCohortLabel(null);
                userRepo.save(user);
            }
        }
        log.info("[Centre] user={} left class={} stillInCentre={}", userId, classId, stillInCentre);

        Map<String, Object> out = new HashMap<>();
        out.put("classId", classId);
        out.put("leftCentre", !stillInCentre);
        return out;
    }

    // ── Admin-side: roster ────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> roster(String userId, String orgId, String cohort, int page, int size) {
        OrganizationJpaEntity orgEntity = accessService.ensureOwner(userId, orgId);
        // Page-size cap protects against pulling the whole roster in one shot.
        int safeSize = Math.max(1, Math.min(size, 200));
        var pageable = org.springframework.data.domain.PageRequest.of(
                Math.max(0, page), safeSize,
                org.springframework.data.domain.Sort.by("createdAt").descending());
        Page<UserJpaEntity> students = (cohort == null || cohort.isBlank())
                ? userRepo.findByCentreId(orgId, pageable)
                : userRepo.findByCentreIdAndCohortLabel(orgId, cohort, pageable);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UserJpaEntity s : students) {
            rows.add(Map.of(
                    "userId", s.getId(),
                    "displayName", s.getDisplayName() == null ? "" : s.getDisplayName(),
                    "level", s.getLevel(),
                    "xp", s.getXp(),
                    "streakDays", s.getStreakDays(),
                    "cohortLabel", s.getCohortLabel() == null ? "" : s.getCohortLabel()));
        }
        return Map.of(
                "organization", Map.of(
                        "id", orgEntity.getId(),
                        "name", orgEntity.getName(),
                        "seatLimit", orgEntity.getSeatLimit()),
                "students", rows,
                "page", students.getNumber(),
                "size", students.getSize(),
                "totalElements", students.getTotalElements(),
                "totalPages", students.getTotalPages());
    }

    // ── Admin-side: analytics (weakest-topic roll-up scoped to centre) ─

    @Transactional(readOnly = true)
    public Map<String, Object> analytics(String userId, String orgId, String cohort) {
        accessService.ensureOwner(userId, orgId);
        String cohortFilter = (cohort == null || cohort.isBlank()) ? null : cohort;
        long studentCount = cohortFilter == null
                ? userRepo.countByCentreId(orgId)
                : userRepo.countByCentreIdAndCohortLabel(orgId, cohortFilter);

        List<Object[]> rows;
        try {
            rows = quizResultRepo.findWeakestTopicsForCentre(orgId, cohortFilter, 10);
        } catch (Exception e) {
            log.warn("[Centre] weak-topic query failed org={}: {}", orgId, e.getMessage());
            rows = List.of();
        }
        List<Map<String, Object>> weakDtos = new ArrayList<>();
        for (Object[] r : rows) {
            weakDtos.add(Map.of(
                    "topic", r[0],
                    "avgMastery", ((Number) r[1]).doubleValue(),
                    "studentsAffected", ((Number) r[2]).intValue()));
        }
        return Map.of("studentCount", studentCount, "weakestTopics", weakDtos);
    }

    // ── Admin-side: CSV export (returns the raw CSV body) ─────────────

    @Transactional(readOnly = true)
    public String exportCsv(String userId, String orgId, String cohort, String format) {
        accessService.ensureOwner(userId, orgId);
        if (!"csv".equalsIgnoreCase(format)) {
            // PDF is a follow-up; honest 501 here keeps the contract clean.
            throw new BusinessException("Only CSV export is supported in v1", 501);
        }
        List<UserJpaEntity> students = cohort == null || cohort.isBlank()
                ? userRepo.findByCentreId(orgId)
                : userRepo.findByCentreIdAndCohortLabel(orgId, cohort);
        StringBuilder sb = new StringBuilder("userId,displayName,cohort,level,xp,streakDays\n");
        for (UserJpaEntity s : students) {
            sb.append(s.getId()).append(',')
                    .append(escape(s.getDisplayName())).append(',')
                    .append(escape(s.getCohortLabel())).append(',')
                    .append(s.getLevel()).append(',')
                    .append(s.getXp()).append(',')
                    .append(s.getStreakDays()).append('\n');
        }
        return sb.toString();
    }

    // ── Admin-side: mint enrollment code ──────────────────────────────

    @Transactional
    public Map<String, Object> mintCode(String userId, String orgId, Map<String, Object> body) {
        accessService.ensureOwner(userId, orgId);
        String cohortLabel = body == null ? null : (String) body.get("cohortLabel");
        if (cohortLabel == null || cohortLabel.isBlank()) {
            throw new BusinessException("cohortLabel is required", 400);
        }
        int seats = 30;
        if (body.get("seats") instanceof Number n) {
            seats = Math.max(1, Math.min(500, n.intValue()));
        }
        CentreEnrollCodeJpaEntity entity = new CentreEnrollCodeJpaEntity();
        entity.setCode(generateCode());
        entity.setOrganizationId(orgId);
        entity.setCohortLabel(cohortLabel);
        entity.setMaxUses(seats);
        entity.setUses(0);
        entity.setExpiresAt(Instant.now().plus(60, ChronoUnit.DAYS));
        entity.setCreatedAt(Instant.now());
        codeRepo.save(entity);
        return Map.of(
                "code", entity.getCode(),
                "cohortLabel", cohortLabel,
                "maxUses", seats,
                "expiresAt", entity.getExpiresAt().toString());
    }

    // ── Internal/admin: create an org + promote owner ─────────────────

    @Transactional
    public Map<String, Object> createOrg(Map<String, Object> body, String adminSecret) {
        adminSecretGuard.require(adminSecret);
        String name = (String) body.get("name");
        String ownerEmail = (String) body.get("ownerEmail");
        if (name == null || name.isBlank() || ownerEmail == null) {
            throw new BusinessException("name + ownerEmail required", 400);
        }
        UserJpaEntity owner = userRepo.findByEmail(ownerEmail).orElseThrow(
                () -> new BusinessException("Owner email not found", 404));
        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId(com.pally.shared.util.IdGenerator.newId());
        org.setName(name);
        org.setOwnerUserId(owner.getId());
        int seatLimit = 30;
        if (body.get("seatLimit") instanceof Number n) {
            seatLimit = Math.max(1, n.intValue());
        }
        org.setSeatLimit(seatLimit);
        org.setCreatedAt(Instant.now());
        orgRepo.save(org);
        // Do NOT write account_type = "CENTRE_ADMIN" — it overflows VARCHAR(10);
        // centre access is gated by owner_user_id, not account_type.
        log.info("[Centre] admin created org={} owner={}", org.getId(), owner.getId());
        return Map.of(
                "id", org.getId(),
                "name", org.getName(),
                "ownerUserId", org.getOwnerUserId());
    }

    // ── Self-serve web onboarding: create my centre ───────────────────

    @Transactional
    public Map<String, Object> onboard(String userId, Map<String, Object> body) {
        var existing = orgRepo.findFirstByOwnerUserId(userId);
        if (existing.isPresent()) {
            OrganizationJpaEntity org = existing.get();
            return Map.of(
                    "orgId", org.getId(),
                    "orgName", org.getName(),
                    "alreadyOwned", true);
        }

        String name = body == null ? null : (String) body.get("centreName");
        if (name == null || name.isBlank()) {
            throw new BusinessException("centreName is required", 400);
        }
        // Validate the caller exists; ownership is recorded on the org itself.
        userRepo.findById(userId).orElseThrow(
                () -> new BusinessException("User not found", 404));

        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId(com.pally.shared.util.IdGenerator.newId());
        org.setName(name.trim());
        org.setOwnerUserId(userId);
        org.setSeatLimit(30);
        org.setCreatedAt(Instant.now());
        orgRepo.save(org);

        // Deliberately NOT writing account_type = "CENTRE_ADMIN" (VARCHAR(10)
        // overflow); centre auth is the owner_user_id check. Owner's centreId
        // stays null so they aren't counted as a student seat.
        log.info("[Centre] self-serve onboard org={} owner={}", org.getId(), userId);
        return Map.of(
                "orgId", org.getId(),
                "orgName", org.getName(),
                "alreadyOwned", false);
    }

    // ── Owner dashboard: which centre am I managing? ──────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> me(String userId) {
        OrganizationJpaEntity org = orgRepo.findFirstByOwnerUserId(userId)
                .orElseThrow(() -> new BusinessException("No centre access", 403));
        long seats = userRepo.countByCentreId(org.getId());
        List<String> cohorts = userRepo.findByCentreId(org.getId())
                .stream()
                .map(u -> u.getCohortLabel() == null ? "" : u.getCohortLabel())
                .filter(s -> !s.isBlank())
                .distinct().sorted().toList();
        return Map.of(
                "orgId", org.getId(),
                "orgName", org.getName(),
                "seatsUsed", seats,
                "seatLimit", org.getSeatLimit(),
                "cohorts", cohorts);
    }

    // ── Per-centre observability: quick activity summary ──────────────

    @Transactional(readOnly = true)
    public Map<String, Object> activity(String userId, String orgId, String since) {
        accessService.ensureOwner(userId, orgId);
        Instant sinceInstant;
        try {
            sinceInstant = (since != null && !since.isBlank())
                    ? Instant.parse(since)
                    : Instant.now().minus(7, ChronoUnit.DAYS);
        } catch (DateTimeParseException e) {
            sinceInstant = Instant.now().minus(7, ChronoUnit.DAYS);
        }
        long count = quizResultRepo.countResultsForCentreSince(orgId, sinceInstant);
        log.info("[Centre] activity org={} since={} count={}", orgId, sinceInstant, count);
        return Map.of(
                "quizResultCount", count,
                "activeSince", sinceInstant.toString());
    }

    // ── Admin-side: mark an avatar as a centre Mochi ──────────────────

    @Transactional
    public Map<String, Object> markCentre(String userId, String orgId, String avatarId) {
        accessService.ensureOwner(userId, orgId);
        var avatarOpt = avatarJpaRepository.findById(avatarId);
        if (avatarOpt.isEmpty()) throw new BusinessException("Avatar not found", 404);
        var avatar = avatarOpt.get();
        boolean inOrg = userRepo.findById(avatar.getUserId())
                .map(u -> orgId.equals(u.getCentreId())).orElse(false);
        if (!inOrg) throw new BusinessException("Avatar not in this org", 403);
        avatar.setCentreAvatar(true);
        avatarJpaRepository.save(avatar);
        log.info("[Centre] Marked avatar={} as centre_avatar for org={}", avatarId, orgId);
        return Map.of("avatarId", avatarId, "centreAvatar", true);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private String generateCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            char[] buf = new char[CODE_LEN];
            for (int i = 0; i < CODE_LEN; i++) {
                buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
            }
            String c = new String(buf);
            if (codeRepo.findById(c).isEmpty()) return c;
        }
        throw new BusinessException("Could not allocate code — try again", 503);
    }

    private String escape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
