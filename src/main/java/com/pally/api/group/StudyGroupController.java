package com.pally.api.group;

import com.pally.infrastructure.persistence.flag.UserFeatureFlagJpaRepository;
import com.pally.infrastructure.persistence.group.GroupMemberJpaEntity;
import com.pally.infrastructure.persistence.group.GroupMemberJpaRepository;
import com.pally.infrastructure.persistence.group.GroupSharedNoteJpaEntity;
import com.pally.infrastructure.persistence.group.GroupSharedNoteJpaRepository;
import com.pally.infrastructure.persistence.group.StudyGroupJpaEntity;
import com.pally.infrastructure.persistence.group.StudyGroupJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pilot-gated collaborative study groups. The {@code groups_enabled}
 * feature flag (V24) controls visibility client-side; backend stays open
 * to any authenticated caller so we can pilot without admin gating logic.
 *
 * <p>Free tier caps at 1 group membership; premium gating is server-side
 * intent and currently only enforced as a hard cap of {@link #FREE_GROUP_CAP}
 * for any non-flagged user. Flagged users (pilot/premium) bypass the cap.
 */
@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
@Slf4j
public class StudyGroupController {

    private static final int FREE_GROUP_CAP = 1;
    private static final String PILOT_FLAG = "groups_enabled";

    private final StudyGroupJpaRepository groupRepo;
    private final GroupMemberJpaRepository memberRepo;
    private final GroupSharedNoteJpaRepository sharedNoteRepo;
    private final UserJpaRepository userRepo;
    private final UserFeatureFlagJpaRepository flagRepo;

    // ── List ────────────────────────────────────────────────────────────

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> listMyGroups(
            @AuthenticationPrincipal String userId) {
        var groups = groupRepo.findGroupsForUser(userId);
        var summaries = groups.stream().map(this::toSummary).toList();
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("groups", summaries)));
    }

    // ── Create ──────────────────────────────────────────────────────────

    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> createGroup(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        ensureWithinCap(userId);
        String name = body.getOrDefault("name", "").trim();
        if (name.isBlank() || name.length() > 100) {
            throw new BusinessException(
                    "Group name is required (1-100 chars)", 400);
        }
        String subject = body.get("subject");

        StudyGroupJpaEntity g = new StudyGroupJpaEntity();
        g.setId(IdGenerator.newId());
        g.setName(name);
        g.setSubject(subject);
        g.setInviteCode(uniqueInviteCode());
        g.setCreatedBy(userId);
        g.setCreatedAt(Instant.now());
        groupRepo.save(g);

        addMember(g.getId(), userId, GroupMemberJpaEntity.ROLE_OWNER);

        log.info("[Groups] user={} created group={} name={}",
                userId, g.getId(), name);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(toSummary(g)));
    }

    // ── Join ────────────────────────────────────────────────────────────

    @PostMapping("/join")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> join(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        ensureWithinCap(userId);
        String inviteCode = body.getOrDefault("inviteCode", "").trim()
                .toUpperCase();
        if (inviteCode.isBlank()) {
            throw new BusinessException("Invite code is required", 400);
        }
        var group = groupRepo.findByInviteCode(inviteCode)
                .orElseThrow(() -> new BusinessException(
                        "Invite code not found", 404));
        if (memberRepo.existsByGroupIdAndUserId(group.getId(), userId)) {
            return ResponseEntity.ok(ApiResponse.success(toSummary(group)));
        }
        addMember(group.getId(), userId, GroupMemberJpaEntity.ROLE_MEMBER);
        log.info("[Groups] user={} joined group={}", userId, group.getId());
        return ResponseEntity.ok(ApiResponse.success(toSummary(group)));
    }

    // ── Detail ──────────────────────────────────────────────────────────

    @GetMapping("/{groupId}")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> getGroup(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId) {
        ensureMember(groupId, userId);
        var group = groupRepo.findById(groupId)
                .orElseThrow(() -> new BusinessException("Group not found", 404));

        List<Map<String, Object>> members = new ArrayList<>();
        for (var m : memberRepo.findByGroupId(groupId)) {
            var u = userRepo.findById(m.getUserId()).orElse(null);
            members.add(Map.of(
                    "userId", m.getUserId(),
                    "displayName", u != null && u.getDisplayName() != null
                            ? u.getDisplayName()
                            : "Member",
                    "role", m.getRole(),
                    "joinedAt", m.getJoinedAt().toString()));
        }

        List<Map<String, Object>> notes = sharedNoteRepo
                .findRecentByGroupId(groupId).stream()
                .map(n -> Map.<String, Object>of(
                        "id", n.getId(),
                        "wikiPageId", n.getWikiPageId(),
                        "title", n.getTitle() == null ? "" : n.getTitle(),
                        "sharedBy", n.getSharedBy(),
                        "sharedAt", n.getSharedAt().toString()))
                .toList();

        Map<String, Object> response = new HashMap<>(toSummary(group));
        response.put("members", members);
        response.put("sharedNotes", notes);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ── Share a wiki page ───────────────────────────────────────────────

    @PostMapping("/{groupId}/share")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> shareNote(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId,
            @RequestBody Map<String, String> body) {
        ensureMember(groupId, userId);
        String wikiPageId = body.get("wikiPageId");
        if (wikiPageId == null || wikiPageId.isBlank()) {
            throw new BusinessException("wikiPageId is required", 400);
        }
        GroupSharedNoteJpaEntity n = new GroupSharedNoteJpaEntity();
        n.setId(IdGenerator.newId());
        n.setGroupId(groupId);
        n.setWikiPageId(wikiPageId);
        n.setTitle(body.get("title"));
        n.setSharedBy(userId);
        n.setSharedAt(Instant.now());
        sharedNoteRepo.save(n);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(Map.of(
                        "id", n.getId(),
                        "wikiPageId", n.getWikiPageId(),
                        "title", n.getTitle() == null ? "" : n.getTitle())));
    }

    // ── Leave ───────────────────────────────────────────────────────────

    @DeleteMapping("/{groupId}/leave")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> leave(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId) {
        memberRepo.deleteByGroupIdAndUserId(groupId, userId);
        log.info("[Groups] user={} left group={}", userId, groupId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void addMember(String groupId, String userId, String role) {
        GroupMemberJpaEntity m = new GroupMemberJpaEntity();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setRole(role);
        m.setJoinedAt(Instant.now());
        memberRepo.save(m);
    }

    private void ensureMember(String groupId, String userId) {
        if (!memberRepo.existsByGroupIdAndUserId(groupId, userId)) {
            throw new BusinessException(
                    "You are not a member of this group", 403);
        }
    }

    private void ensureWithinCap(String userId) {
        if (isPilotUser(userId)) return;
        long current = memberRepo.countByUserId(userId);
        if (current >= FREE_GROUP_CAP) {
            throw new BusinessException(
                    "Free tier is capped at " + FREE_GROUP_CAP
                            + " group. Upgrade to join more.", 402);
        }
    }

    private boolean isPilotUser(String userId) {
        return flagRepo.findByUserIdAndFlagName(userId, PILOT_FLAG)
                .map(r -> r.isEnabled())
                .orElse(false);
    }

    private Map<String, Object> toSummary(StudyGroupJpaEntity g) {
        long memberCount = memberRepo.findByGroupId(g.getId()).size();
        Map<String, Object> m = new HashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("subject", g.getSubject());
        m.put("inviteCode", g.getInviteCode());
        m.put("createdBy", g.getCreatedBy());
        m.put("createdAt", g.getCreatedAt().toString());
        m.put("memberCount", memberCount);
        return m;
    }

    private String uniqueInviteCode() {
        // 6-char alphanumeric. Retry on the rare collision; uniqueness is
        // enforced by the DB index.
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int attempt = 0; attempt < 8; attempt++) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                sb.append(alphabet.charAt(
                        ThreadLocalRandom.current().nextInt(alphabet.length())));
            }
            String code = sb.toString();
            if (!groupRepo.existsByInviteCode(code)) return code;
        }
        throw new BusinessException("Could not generate invite code", 500);
    }
}
