package com.pally.api.admin;

import com.pally.domain.syllabus.SyllabusContentPack;
import com.pally.domain.syllabus.SyllabusContentPackAlias;
import com.pally.domain.syllabus.SyllabusContentPackService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Platform-admin review for syllabus_content_pack items. Gated by SecurityConfig's
 * {@code /api/v1/admin/**} -> hasRole("ADMIN") rule, no route-specific change needed.
 * This IS the pre-moderated gate: a pack's generated items stay DRAFT (never servable)
 * until an admin calls {@link #approveItems}, and the pack itself stays invisible to
 * "browse starter content" until an admin calls {@link #publish}.
 */
@RestController
@RequestMapping("/api/v1/admin/syllabus-packs")
@RequiredArgsConstructor
public class SyllabusPackAdminController {

    private final SyllabusContentPackService packService;

    @PostMapping("/{packId}/approve-items")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> approveItems(
            @PathVariable String packId, @RequestBody List<String> itemIds) {
        int approved = packService.approveItems(packId, itemIds);
        return ResponseEntity.ok(ApiResponse.success(Map.of("approved", approved)));
    }

    @PostMapping("/{packId}/publish")
    public ResponseEntity<ApiResponse<SyllabusContentPack>> publish(@PathVariable String packId) {
        return ResponseEntity.ok(ApiResponse.success(packService.publish(packId)));
    }

    /**
     * Makes an existing pack additionally discoverable under a second syllabus's topic
     * tag, without generating a second copy of its content (Phase 2 cross-syllabus reuse).
     */
    @PostMapping("/{packId}/alias")
    public ResponseEntity<ApiResponse<SyllabusContentPackAlias>> addAlias(
            @PathVariable String packId, @RequestBody AddAliasRequest request) {
        SyllabusContentPackAlias alias =
                packService.addAlias(packId, request.syllabusCode(), request.topicTag());
        return ResponseEntity.ok(ApiResponse.success(alias));
    }

    public record AddAliasRequest(String syllabusCode, String topicTag) {
    }
}
