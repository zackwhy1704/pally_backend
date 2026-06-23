package com.pally.api.app;

import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionControllerTest {

    @Test
    void minVersion_returnsConfiguredPerPlatformVersions() {
        AppVersionController controller = new AppVersionController("2.3.0", "2.1.0");

        ResponseEntity<ApiResponse<Map<String, String>>> res = controller.minVersion();

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().data())
                .containsEntry("ios", "2.3.0")
                .containsEntry("android", "2.1.0");
    }
}
