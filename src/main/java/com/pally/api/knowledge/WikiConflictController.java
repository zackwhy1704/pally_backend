package com.pally.api.knowledge;

import com.pally.domain.knowledge.WikiConflictService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Teacher-facing fact-conflict queue (Part A): list open conflicts for a brain
 * (DETERMINISTIC first) and resolve one by choosing the canonical content.
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}/conflicts")
@RequiredArgsConstructor
public class WikiConflictController {

    private final WikiConflictService wikiConflictService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<WikiConflictService.ConflictSummary>>> list(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        return ResponseEntity.ok(ApiResponse.success(
                wikiConflictService.listOpen(avatarId, userId)));
    }

    @PostMapping("/{conflictId}/resolve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resolve(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String conflictId,
            @RequestBody Map<String, String> body) {
        wikiConflictService.resolve(avatarId, conflictId, userId,
                body == null ? null : body.get("canonicalValue"));
        return ResponseEntity.ok(ApiResponse.success(Map.of("status", "RESOLVED")));
    }
}
