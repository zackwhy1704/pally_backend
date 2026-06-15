package com.pally.api.curriculum;

import com.pally.domain.curriculum.CurriculumService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Thin curriculum controller — delegates all logic to {@link CurriculumService}.
 *
 * <p>v1 surfaces:
 *  - List curricula visible to the caller (built-in shared + own uploads).
 *  - List topics inside a curriculum.
 *  - Upload a syllabus PDF/text → Claude parses → persists as a new curriculum.
 *  - Attach/detach a curriculum from an avatar.
 */
@RestController
@RequestMapping("/api/v1/curricula")
@RequiredArgsConstructor
public class CurriculumController {

    private final CurriculumService curriculumService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) String subject) {
        return ResponseEntity.ok(
                ApiResponse.success(curriculumService.listCurricula(userId, subject)));
    }

    @GetMapping("/{curriculumId}/topics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> topics(
            @AuthenticationPrincipal String userId,
            @PathVariable String curriculumId) {
        return ResponseEntity.ok(
                ApiResponse.success(curriculumService.getTopics(userId, curriculumId)));
    }

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) String text,
            @RequestParam String subject,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String name) throws Exception {
        Map<String, Object> result = curriculumService.uploadSyllabus(
                userId,
                (file != null && !file.isEmpty()) ? file.getInputStream() : null,
                text,
                subject,
                grade,
                name);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    @PostMapping("/avatars/{avatarId}/attach")
    public ResponseEntity<ApiResponse<Map<String, Object>>> attachToAvatar(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestBody Map<String, String> body) {
        String curriculumId = body != null ? body.get("curriculumId") : null;
        return ResponseEntity.ok(
                ApiResponse.success(curriculumService.attachToAvatar(userId, avatarId, curriculumId)));
    }
}
