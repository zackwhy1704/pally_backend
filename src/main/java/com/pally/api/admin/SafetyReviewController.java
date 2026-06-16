package com.pally.api.admin;

import com.pally.infrastructure.persistence.safety.ChatSafetyFlagJpaEntity;
import com.pally.infrastructure.persistence.safety.ChatSafetyFlagJpaRepository;
import com.pally.shared.AdminSecretGuard;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
public class SafetyReviewController {

    private final AdminSecretGuard adminSecretGuard;
    private final ChatSafetyFlagJpaRepository flagRepo;

    @GetMapping("/safety-flags")
    public ResponseEntity<ApiResponse<List<SafetyFlagDto>>> getSafetyFlags(
            @RequestHeader(value = "X-Admin-Secret", required = false) String adminSecret,
            @RequestParam String childUserId,
            @RequestParam(defaultValue = "24") int sinceHours) {

        adminSecretGuard.require(adminSecret);
        Instant since = Instant.now().minus(sinceHours, ChronoUnit.HOURS);
        List<ChatSafetyFlagJpaEntity> flags =
                flagRepo.findByChildUserIdAndCreatedAtAfterOrderByCreatedAtDesc(childUserId, since);
        List<SafetyFlagDto> dtos = flags.stream()
                .map(f -> new SafetyFlagDto(
                        f.getId(), f.getCategory(), f.getSeverity(),
                        f.getSnippet(), f.getSource(), f.getCreatedAt(),
                        f.getMessageId(), f.getAvatarId()))
                .collect(Collectors.toList());
        log.info("[SafetyReview] queried flags user={} sinceHours={} count={}",
                childUserId, sinceHours, dtos.size());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }
}
