package com.pally.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for {@code POST /api/v1/subscription/upgrade-link}.
 *
 * <p>In the test profile both {@code EmailService} and {@code FcmService} are
 * {@code @MockBean}s whose {@code isConfigured()} returns {@code false} by
 * default, so no real provider is hit and both channels report {@code false} —
 * we assert the response SHAPE and auth behaviour, not external delivery.
 */
class UpgradeLinkIntegrationTest extends IntegrationTestBase {

    private static final String PATH = "/api/v1/subscription/upgrade-link";

    private String registerToken() {
        String email = "upgrade-" + UUID.randomUUID() + "@test.com";
        return registerUser(email, "password123").token();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentMap(ResponseEntity<Map> resp) {
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        return (Map<String, Object>) data.get("sent");
    }

    @Test
    void upgradeLink_defaultBody_returnsSentMapWithBothChannelKeys() {
        ResponseEntity<Map> resp = post(PATH, registerToken(), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> sent = sentMap(resp);
        assertThat(sent).containsOnlyKeys("email", "push");
        // No provider configured in the test profile → both false, but present.
        assertThat(sent.get("email")).isEqualTo(false);
        assertThat(sent.get("push")).isEqualTo(false);
    }

    @Test
    void upgradeLink_emailOnly_returnsSentMapShape() {
        ResponseEntity<Map> resp = post(PATH, registerToken(),
                Map.of("channels", List.of("email")));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sentMap(resp)).containsOnlyKeys("email", "push");
    }

    @Test
    void upgradeLink_pushOnly_returnsSentMapShape() {
        ResponseEntity<Map> resp = post(PATH, registerToken(),
                Map.of("channels", List.of("push")));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sentMap(resp)).containsOnlyKeys("email", "push");
    }

    @Test
    void upgradeLink_withoutToken_returns401() {
        RestTemplate plain = new RestTemplate();
        try {
            ResponseEntity<Map> resp = plain.exchange(
                    baseUrl() + PATH,
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("channels", List.of("email")), new HttpHeaders()),
                    Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
