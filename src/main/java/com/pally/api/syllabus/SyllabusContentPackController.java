package com.pally.api.syllabus;

import com.pally.domain.syllabus.SyllabusContentPackService;
import com.pally.domain.syllabus.dto.PackBrowseView;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Student/teacher-facing "browse starter content" — a selectable alternative to
 * uploading your own material, never the forced/primary path (upload stays default).
 * Any authenticated user may browse (no org/class scoping — packs are platform-global);
 * gated by {@code anyRequest().authenticated()} in SecurityConfig, no route change needed.
 */
@RestController
@RequestMapping("/api/v1/content-packs")
@RequiredArgsConstructor
public class SyllabusContentPackController {

    private final SyllabusContentPackService packService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<PackBrowseView>>> browse(
            @RequestParam(required = false) String syllabus) {
        return ResponseEntity.ok(ApiResponse.success(packService.browsePublished(syllabus)));
    }
}
