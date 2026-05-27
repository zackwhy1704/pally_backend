package com.pally.api.auth;

import com.pally.infrastructure.auth.BiometricAuthService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/biometric")
@RequiredArgsConstructor
public class BiometricAuthController {

    private final BiometricAuthService biometricAuthService;

    @PostMapping("/challenge")
    public ResponseEntity<ApiResponse<Map<String, Object>>> challenge(
            @AuthenticationPrincipal String userId) {
        var result = biometricAuthService.createChallenge(userId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @RequestBody Map<String, String> request) {
        var result = biometricAuthService.verifyBiometric(
                request.get("userId"), request.get("deviceId"));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> request) {
        biometricAuthService.registerDevice(userId, request.get("deviceId"), request.get("deviceName"));
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("registered", true, "deviceId", request.get("deviceId"))));
    }
}
