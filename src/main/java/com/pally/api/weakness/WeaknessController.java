package com.pally.api.weakness;

import com.pally.domain.avatar.Subject;
import com.pally.domain.weakness.TeacherWeaknessService;
import com.pally.domain.weakness.WeaknessProfileService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Student-facing read of their own WEAKNESS_PROFILE — the "what to focus on next"
 * + "you improved" surface that visibly closes the LEARN→TEST→PROVE loop. Private
 * to the authenticated student; returns empty focus areas when the pilot flag is off.
 */
@RestController
@RequestMapping("/api/v1/weakness")
@RequiredArgsConstructor
public class WeaknessController {

    private final WeaknessProfileService weaknessProfileService;
    private final TeacherWeaknessService teacherWeaknessService;

    @GetMapping("/focus")
    public ApiResponse<Map<String, Object>> focus(
            @AuthenticationPrincipal String userId,
            @RequestParam Subject subject) {
        return ApiResponse.success(weaknessProfileService.focusFor(userId, subject));
    }

    /** Teacher (class owner) view: per-student weak areas for the class subject. */
    @GetMapping("/class/{classId}")
    public ApiResponse<Map<String, Object>> classWeakness(
            @AuthenticationPrincipal String userId,
            @PathVariable String classId) {
        return ApiResponse.success(
                teacherWeaknessService.perStudentWeakness(userId, classId));
    }
}
