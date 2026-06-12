package com.pally.api;

import com.pally.domain.consent.ConsentGuard;
import com.pally.shared.exception.AiConsentRequiredException;
import com.pally.shared.exception.ConsentRequiredException;
import com.pally.shared.exception.GuardianRequiredException;
import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the GlobalExceptionHandler maps the two consent gates to distinct,
 * client-recognisable error codes:
 *
 * <ul>
 *   <li>{@link AiConsentRequiredException} → 403 with code {@code AI_CONSENT_REQUIRED}
 *       (so the Flutter client shows the AI-disclosure screen).</li>
 *   <li>The parental {@link ConsentRequiredException} → 403 with code
 *       {@code CONSENT_REQUIRED} (parent-approval gate) — must NOT regress.</li>
 * </ul>
 */
class GlobalExceptionHandlerConsentTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @SuppressWarnings("unchecked")
    void aiConsentRequired_mapsTo403_withAiConsentRequiredCode() {
        AiConsentRequiredException ex =
                new AiConsentRequiredException(ConsentGuard.REASON_AI_DATA_TRANSFER);

        ResponseEntity<ApiResponse<Map<String, Object>>> resp =
                handler.handleAiConsentRequired(ex);

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        ApiResponse<Map<String, Object>> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(403);
        assertThat(body.data()).containsEntry("code", "AI_CONSENT_REQUIRED");
        assertThat(body.data()).containsEntry("reason", "AI_DATA_TRANSFER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void guardianRequired_mapsTo403_withParentLinkRequiredCode() {
        GuardianRequiredException ex =
                new GuardianRequiredException(ConsentGuard.REASON_PARENT_LINK_REQUIRED);

        ResponseEntity<ApiResponse<Map<String, Object>>> resp =
                handler.handleGuardianRequired(ex);

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        ApiResponse<Map<String, Object>> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(403);
        assertThat(body.data()).containsEntry("code", "PARENT_LINK_REQUIRED");
        assertThat(body.data()).containsEntry("reason", "PARENT_LINK_REQUIRED");
    }

    @Test
    void parentalConsentRequired_stillMapsToConsentRequiredCode() {
        ConsentRequiredException ex = new ConsentRequiredException("UPLOAD");

        ResponseEntity<ApiResponse<Map<String, Object>>> resp =
                handler.handleConsentRequired(ex);

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        ApiResponse<Map<String, Object>> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.data()).containsEntry("code", "CONSENT_REQUIRED");
        assertThat(body.data()).containsEntry("reason", "UPLOAD");
    }
}
