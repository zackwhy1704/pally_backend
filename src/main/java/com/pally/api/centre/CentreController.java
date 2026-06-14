package com.pally.api.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.organization.ClassEnrollmentService;
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
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.format.DateTimeParseException;

/**
 * Centre (B2B) endpoints. The Flutter app only consumes
 * {@code POST /redeem-enroll-code}; the rest are JSON the future admin
 * web dashboard will consume.
 *
 * <p>Authorization: every {@code /organizations/{orgId}/*} call asserts
 * the caller IS the org's {@code owner_user_id} — 403 otherwise. This is
 * stronger than the existing feature-flag toggle and is the right model
 * for billing/roster data.
 */
@RestController
@RequestMapping("/api/v1/centre")
@RequiredArgsConstructor
@Slf4j
public class CentreController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LEN = 6;

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

    @PostMapping("/redeem-enroll-code")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> redeem(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
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
        // Atomic check-and-increment so the seat cap holds under concurrent
        // students redeeming at the same moment.
        int updated = codeRepo.incrementUses(code);
        if (updated == 0) {
            throw new BusinessException(
                    "This code has reached its seat limit", 409);
        }
        UserJpaEntity user = userRepo.findById(userId).orElseThrow(
                () -> new BusinessException("User not found", 404));
        user.setCentreId(row.getOrganizationId());
        user.setCohortLabel(row.getCohortLabel());
        userRepo.save(user);
        log.info("[Centre] user={} joined org={} cohort={}",
                userId, row.getOrganizationId(), row.getCohortLabel());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "organizationId", row.getOrganizationId(),
                "cohortLabel", row.getCohortLabel())));
    }

    // ── Student-side: join a class directly by its class code ─────────

    /**
     * Self-join a class with the single code printed on the class card. The class
     * code doubles as the centre invite: redeeming it sets the student's centre,
     * then provisions their branded class avatar + membership + CLASS-group join
     * (idempotent — re-entering the code is a no-op that returns the same avatar).
     */
    @PostMapping("/redeem-class-code")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> redeemClassCode(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
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
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    // ── Student-side: leave a class ───────────────────────────────────

    /**
     * Self-leave a class: removes the caller's membership + their branded class
     * avatar for that class (their personal Mochis are untouched), and drops them
     * from the class group. The corpus avatar and other students are never touched.
     * If this was their only class in the centre, their {@code centreId} is cleared.
     */
    @PostMapping("/leave-class")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> leaveClass(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String classId = body == null ? null : body.get("classId");
        if (classId == null || classId.isBlank()) {
            throw new BusinessException("classId is required", 400);
        }
        OrgClassJpaEntity cls = classRepo.findById(classId).orElseThrow(
                () -> new BusinessException("That class doesn't exist", 404));

        classEnrollmentService.leave(cls, userId);

        // Clear centre membership only if the student has no other active class
        // in the SAME centre.
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
        log.info("[Centre] user={} left class={} stillInCentre={}",
                userId, classId, stillInCentre);

        Map<String, Object> out = new HashMap<>();
        out.put("classId", classId);
        out.put("leftCentre", !stillInCentre);
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    // ── Admin-side: roster ────────────────────────────────────────────

    @GetMapping("/organizations/{orgId}/roster")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> roster(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestParam(required = false) String cohort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        // Local var avoids the standard "org" name because it shadows the
        // org.springframework.* package below.
        OrganizationJpaEntity orgEntity = accessService.ensureOwner(userId, orgId);
        // Page-size cap protects a misbehaving caller from yanking the
        // whole roster in one shot; default 50 fits a typical cohort.
        int safeSize = Math.max(1, Math.min(size, 200));
        var pageable = org.springframework.data.domain.PageRequest.of(
                Math.max(0, page), safeSize,
                org.springframework.data.domain.Sort
                        .by("createdAt").descending());
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
                    "cohortLabel",
                            s.getCohortLabel() == null ? "" : s.getCohortLabel()));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "organization", Map.of(
                        "id", orgEntity.getId(),
                        "name", orgEntity.getName(),
                        "seatLimit", orgEntity.getSeatLimit()),
                "students", rows,
                "page", students.getNumber(),
                "size", students.getSize(),
                "totalElements", students.getTotalElements(),
                "totalPages", students.getTotalPages())));
    }

    // ── Admin-side: analytics (weakest-topic roll-up scoped to centre) ─

    @GetMapping("/organizations/{orgId}/analytics")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> analytics(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestParam(required = false) String cohort) {
        accessService.ensureOwner(userId, orgId);
        // One cohort-scoped aggregate replaces the previous per-student
        // loop: a 40-student class now hits the DB once instead of 40+
        // times. Cohort filter is "match-or-skip" — null spans the centre.
        String cohortFilter = (cohort == null || cohort.isBlank()) ? null : cohort;
        long studentCount = cohortFilter == null
                ? userRepo.countByCentreId(orgId)
                : userRepo.countByCentreIdAndCohortLabel(orgId, cohortFilter);

        List<Object[]> rows;
        try {
            rows = quizResultRepo.findWeakestTopicsForCentre(orgId, cohortFilter, 10);
        } catch (Exception e) {
            log.warn("[Centre] weak-topic query failed org={}: {}",
                    orgId, e.getMessage());
            rows = List.of();
        }
        List<Map<String, Object>> weakDtos = new ArrayList<>();
        for (Object[] r : rows) {
            weakDtos.add(Map.of(
                    "topic", r[0],
                    "avgMastery", ((Number) r[1]).doubleValue(),
                    "studentsAffected", ((Number) r[2]).intValue()));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "studentCount", studentCount,
                "weakestTopics", weakDtos)));
    }

    // ── Admin-side: CSV export ────────────────────────────────────────

    @GetMapping(value = "/organizations/{orgId}/export",
            produces = "text/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<String> export(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestParam(required = false) String cohort,
            @RequestParam(defaultValue = "csv") String format) {
        accessService.ensureOwner(userId, orgId);
        if (!"csv".equalsIgnoreCase(format)) {
            // PDF is a follow-up; honest 501 here keeps the contract clean.
            throw new BusinessException(
                    "Only CSV export is supported in v1", 501);
        }
        List<UserJpaEntity> students = cohort == null || cohort.isBlank()
                ? userRepo.findByCentreId(orgId)
                : userRepo.findByCentreIdAndCohortLabel(orgId, cohort);
        StringBuilder sb = new StringBuilder(
                "userId,displayName,cohort,level,xp,streakDays\n");
        for (UserJpaEntity s : students) {
            sb.append(s.getId()).append(',')
                    .append(escape(s.getDisplayName())).append(',')
                    .append(escape(s.getCohortLabel())).append(',')
                    .append(s.getLevel()).append(',')
                    .append(s.getXp()).append(',')
                    .append(s.getStreakDays()).append('\n');
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header("Content-Disposition",
                        "attachment; filename=\"roster.csv\"")
                .body(sb.toString());
    }

    // ── Admin-side: mint enrollment code ──────────────────────────────

    @PostMapping("/organizations/{orgId}/enroll-code")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> mintCode(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestBody Map<String, Object> body) {
        accessService.ensureOwner(userId, orgId);
        String cohortLabel = body == null ? null
                : (String) body.get("cohortLabel");
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
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "code", entity.getCode(),
                "cohortLabel", cohortLabel,
                "maxUses", seats,
                "expiresAt", entity.getExpiresAt().toString())));
    }

    // ── Internal/admin: create an org + promote owner ─────────────────

    /// V1 onboarding: someone with the right backend access POSTs this to
    /// flip a known user to CENTRE_ADMIN and seat them as org owner. We
    /// gate with a shared secret so it can't be hit from the kid app.
    @PostMapping("/admin/organizations")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> createOrg(
            @RequestBody Map<String, Object> body,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = "X-Admin-Secret", required = false) String adminSecret) {
        String expected = System.getenv("ADMIN_SECRET");
        if (expected == null || expected.isBlank()
                || !expected.equals(adminSecret)) {
            throw new BusinessException("Admin access required", 403);
        }
        String name = (String) body.get("name");
        String ownerEmail = (String) body.get("ownerEmail");
        if (name == null || name.isBlank() || ownerEmail == null) {
            throw new BusinessException(
                    "name + ownerEmail required", 400);
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
        // Do NOT write account_type = "CENTRE_ADMIN" — it overflows the
        // VARCHAR(10) column and centre access is gated by owner_user_id, not
        // account_type. (This latent bug is why this endpoint 500'd.)
        log.info("[Centre] admin created org={} owner={}", org.getId(), owner.getId());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "id", org.getId(),
                "name", org.getName(),
                "ownerUserId", org.getOwnerUserId())));
    }

    // ── Self-serve web onboarding: create my centre ───────────────────

    /**
     * Self-serve centre creation for the web dashboard. The authenticated caller
     * becomes the owner of a brand-new organization and is promoted to
     * {@code CENTRE_ADMIN}. Idempotent: if the caller already owns an org, that
     * org is returned untouched (so a double-submit or re-onboard is safe).
     *
     * <p>Unlike {@code /admin/organizations} this is NOT secret-gated — any
     * signed-in user may create exactly their own centre. They never gain access
     * to anyone else's org (every other centre route is owner-checked).
     */
    @PostMapping("/onboard")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> onboard(
            @AuthenticationPrincipal String userId,
            @RequestBody(required = false) Map<String, Object> body) {

        var existing = orgRepo.findFirstByOwnerUserId(userId);
        if (existing.isPresent()) {
            OrganizationJpaEntity org = existing.get();
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "orgId", org.getId(),
                    "orgName", org.getName(),
                    "alreadyOwned", true)));
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

        // NOTE: we deliberately do NOT write account_type = "CENTRE_ADMIN".
        // That column is VARCHAR(10) (SOLO/PARENT/CHILD) and "CENTRE_ADMIN"
        // overflows it; more importantly centre authorization is purely the
        // organizations.owner_user_id check in CentreAccessService — account_type
        // is never read for it. The owner's centreId is left null so they aren't
        // counted as a student seat.
        log.info("[Centre] self-serve onboard org={} owner={}", org.getId(), userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "orgId", org.getId(),
                "orgName", org.getName(),
                "alreadyOwned", false)));
    }

    // ── Owner dashboard: which centre am I managing? ──────────────────

    /**
     * Returns the org owned by the calling user together with seat usage
     * and the sorted list of distinct cohort labels.
     * Intended for the first "boot" call from the admin dashboard.
     */
    @GetMapping("/me")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(
            @AuthenticationPrincipal String userId) {
        OrganizationJpaEntity org = orgRepo.findFirstByOwnerUserId(userId)
                .orElseThrow(() -> new BusinessException("No centre access", 403));
        long seats = userRepo.countByCentreId(org.getId());
        List<String> cohorts = userRepo.findByCentreId(org.getId())
                .stream()
                .map(u -> u.getCohortLabel() == null ? "" : u.getCohortLabel())
                .filter(s -> !s.isBlank())
                .distinct().sorted().toList();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "orgId",      org.getId(),
                "orgName",    org.getName(),
                "seatsUsed",  seats,
                "seatLimit",  org.getSeatLimit(),
                "cohorts",    cohorts
        )));
    }

    // ── Per-centre observability: quick activity summary ──────────────

    /**
     * Returns a lightweight count of centre-avatar quiz results since {@code since}
     * (ISO-8601 instant, e.g. {@code 2026-06-01T00:00:00Z}).
     * Defaults to 7 days ago if the param is absent or unparseable.
     * Single DB query — fast enough to poll every 30 s from a dashboard.
     */
    @GetMapping("/organizations/{orgId}/activity")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> activity(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestParam(required = false) String since) {
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
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "quizResultCount", count,
                "activeSince",     sinceInstant.toString()
        )));
    }

    // ── Admin-side: mark an avatar as a centre Mochi ──────────────────

    /**
     * Flags {@code avatarId} as a centre avatar ({@code centre_avatar=true}).
     * The avatar must belong to a student already enrolled in {@code orgId}.
     * Owner-gated: 403 if the caller is not the org's owner.
     */
    @PostMapping("/organizations/{orgId}/avatars/{avatarId}/mark-centre")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> markCentre(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String avatarId) {
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
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "avatarId",    avatarId,
                "centreAvatar", true)));
    }

    private String generateCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            char[] buf = new char[CODE_LEN];
            for (int i = 0; i < CODE_LEN; i++) {
                buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
            }
            String c = new String(buf);
            if (codeRepo.findById(c).isEmpty()) return c;
        }
        throw new BusinessException(
                "Could not allocate code — try again", 503);
    }

    private String escape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
