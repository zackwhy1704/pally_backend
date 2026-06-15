package com.pally.api.home;

import com.pally.domain.home.HomeService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Thin home-screen controller — delegates all logic to {@link HomeService}.
 */
@RestController
@RequestMapping("/api/v1/home")
@RequiredArgsConstructor
public class HomeController {

    private final HomeService homeService;

    @GetMapping("/nudges")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getNudges(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(homeService.getNudges(userId)));
    }
}
