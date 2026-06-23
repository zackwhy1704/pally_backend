package com.pally.api.app;

import com.pally.shared.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forced-update gate (CA-16). Returns the lowest client version the backend still
 * supports per platform. The mobile app fetches this on launch and blocks below it.
 * Public (no auth) — the check runs before login. Defaults to 1.0.0 (no-op) until an
 * operator raises MIN_APP_VERSION_IOS / MIN_APP_VERSION_ANDROID.
 */
@RestController
@RequestMapping("/api/v1/app")
public class AppVersionController {

    private final String minIos;
    private final String minAndroid;

    public AppVersionController(
            @Value("${app.min-version.ios:1.0.0}") String minIos,
            @Value("${app.min-version.android:1.0.0}") String minAndroid) {
        this.minIos = minIos;
        this.minAndroid = minAndroid;
    }

    @GetMapping("/min-version")
    public ResponseEntity<ApiResponse<Map<String, String>>> minVersion() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("ios", minIos);
        body.put("android", minAndroid);
        return ResponseEntity.ok(ApiResponse.success(body));
    }
}
