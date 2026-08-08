package com.pally.api.onboard;

import com.pally.api.onboard.dto.QuickOnboardRequest;
import com.pally.api.onboard.dto.QuickOnboardResponse;
import com.pally.domain.onboard.QuickOnboardService;
import com.pally.shared.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fast onboarding endpoint: register + create avatar in one call.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/onboard")
@RequiredArgsConstructor
public class OnboardController {

    private final QuickOnboardService quickOnboardService;

    /**
     * Quick onboard: combines register (or login) + avatar creation.
     * Returns JWT token, userId, and avatarId in a single response.
     */
    @PostMapping("/quick")
    public ResponseEntity<ApiResponse<QuickOnboardResponse>> quickOnboard(
            @Valid @RequestBody QuickOnboardRequest request
    ) {
        log.info("[Onboard] Quick onboard request email={}", request.email());

        QuickOnboardService.QuickOnboardResult result = quickOnboardService.execute(
                request.email(), request.password(), request.displayName(),
                request.subject(), request.level(), request.role(), request.birthYear(),
                request.parentEmail(), request.contentLanguage(), request.acceptedTerms());

        QuickOnboardResponse response = new QuickOnboardResponse(
                result.token(), result.userId(), result.avatarId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(response));
    }
}
