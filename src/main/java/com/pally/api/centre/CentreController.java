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
            @RequestParam(required = false) String cohort) {
        OrganizationJpaEntity org = ensureOwner(userId, orgId);
        List<UserJpaEntity> students = cohort == null || cohort.isBlank()
                ? userRepo.findByCentreId(orgId)
                : userRepo.findByCentreIdAndCohortLabel(orgId, cohort);
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
                        "id", org.getId(),
                        "name", org.getName(),
                        "seatLimit", org.getSeatLimit()),
                "students", rows)));
    }

    // ── Admin-side: analytics (weakest-topic roll-up scoped to centre) ─

    @GetMapping("/organizations/{orgId}/analytics")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> analytics(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestParam(required = false) String cohort) {
        ensureOwner(userId, orgId);
        // Reuse the existing weakest-topic SQL by aggregating per student.
        // For v1 we walk the in-centre user list and merge their top weak
        // topics; the dedicated cross-user SQL is a future optimization.
        List<UserJpaEntity> students = cohort == null || cohort.isBlank()
                ? userRepo.findByCentreId(orgId)
                : userRepo.findByCentreIdAndCohortLabel(orgId, cohort);
        Map<String, double[]> topicTotals = new HashMap<>(); // topic → [ratioSum, n]
        for (UserJpaEntity s : students) {
            try {
                var rows = quizResultRepo.findWeakestTopics(s.getId(), 5);
                for (Object[] r : rows) {
                    String topic = (String) r[0];
                    double ratio = ((Number) r[1]).doubleValue();
                    double[] agg = topicTotals.computeIfAbsent(topic,
                            k -> new double[]{0.0, 0.0});
                    agg[0] += ratio;
                    agg[1] += 1;
                }
            } catch (Exception ignored) {}
        }
        List<Map<String, Object>> weakDtos = new ArrayList<>();
        topicTotals.forEach((topic, agg) -> weakDtos.add(Map.of(
                "topic", topic,
                "avgMastery", agg[1] == 0 ? 0 : agg[0] / agg[1],
                "studentsAffected", (int) agg[1])));
        weakDtos.sort((a, b) -> Double.compare(
                (double) a.get("avgMastery"),
                (double) b.get("avgMastery")));
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "studentCount", students.size(),
                "weakestTopics", weakDtos.subList(0, Math.min(10, weakDtos.size())))));
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
