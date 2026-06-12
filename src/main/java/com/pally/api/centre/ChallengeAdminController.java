package com.pally.api.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.challenge.ChallengeService;
import com.pally.domain.group.ClassGroupService;
import com.pally.infrastructure.persistence.challenge.ClassChallengeJpaEntity;
import com.pally.infrastructure.persistence.group.GroupSystemPostJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Teacher (centre-gated) endpoint to post a class challenge. Posting also drops
 * a heads-up into the class's CLASS group feed.
 */
@RestController
@RequestMapping("/api/v1/centre/organizations/{orgId}/classes/{classId}/challenges")
@RequiredArgsConstructor
@Slf4j
public class ChallengeAdminController {

    private final CentreAccessService accessService;
    private final ChallengeService challengeService;
    private final ClassGroupService classGroupService;
    private final OrgClassJpaRepository classRepo;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody Map<String, Object> body) {
        accessService.ensureOwner(userId, orgId);
        requireClass(orgId, classId);

        String question = str(body.get("question"));
        String answer = str(body.get("answer"));
        @SuppressWarnings("unchecked")
        List<String> options = body.get("options") instanceof List<?> list
                ? list.stream().map(Object::toString).toList() : null;
        String revealAtStr = str(body.get("revealAt"));
        Instant revealAt = revealAtStr != null ? Instant.parse(revealAtStr) : null;

        ClassChallengeJpaEntity c = challengeService.create(
                classId, question, options, answer, revealAt, userId);

        // Announce in the CLASS group so students see the new challenge.
        classGroupService.postSystemMessage(
                classId, GroupSystemPostJpaEntity.KIND_CHALLENGE,
                "New challenge question is live ⚡ — lock in your answer!", c.getId());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", c.getId());
        resp.put("classId", c.getClassId());
        resp.put("revealAt", c.getRevealAt().toString());
        return ResponseEntity.status(201).body(ApiResponse.created(resp));
    }

    private OrgClassJpaEntity requireClass(String orgId, String classId) {
        OrgClassJpaEntity cls = classRepo.findById(classId)
                .orElseThrow(() -> new BusinessException("Class not found", 404));
        if (!orgId.equals(cls.getOrganizationId())) {
            throw new BusinessException("Class not in this organization", 403);
        }
        return cls;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
