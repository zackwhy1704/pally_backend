package com.pally.api.centre;

import com.pally.infrastructure.persistence.organization.CentreEnrollCodeJpaEntity;
import com.pally.infrastructure.persistence.organization.CentreEnrollCodeJpaRepository;
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

    private final OrganizationJpaRepository orgRepo;
    private final CentreEnrollCodeJpaRepository codeRepo;
    private final UserJpaRepository userRepo;
    private final QuizQuestionResultJpaRepository quizResultRepo;

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
        OrganizationJpaEntity orgEntity = ensureOwner(userId, orgId);
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
        ensureOwner(userId, orgId);
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
        ensureOwner(userId, orgId);
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
        ensureOwner(userId, orgId);
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
        owner.setAccountType("CENTRE_ADMIN");
        userRepo.save(owner);
        log.info("[Centre] admin created org={} owner={}", org.getId(), owner.getId());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "id", org.getId(),
                "name", org.getName(),
                "ownerUserId", org.getOwnerUserId())));
    }

    private OrganizationJpaEntity ensureOwner(String userId, String orgId) {
        OrganizationJpaEntity org = orgRepo.findById(orgId).orElseThrow(
                () -> new BusinessException("Organization not found", 404));
        if (!org.getOwnerUserId().equals(userId)) {
            throw new BusinessException(
                    "You don't own this organization", 403);
        }
        return org;
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
