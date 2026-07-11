package com.pally.api;

import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Over-long / invalid input must surface as 400, never a generic 500 — the failure
 * mode that silently broke avatar creation (grade_level VARCHAR(10) overflow on
 * "Secondary 3"). These map the relevant exceptions to 400 with no schema leak.
 */
class GlobalExceptionHandlerBadInputTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void dataIntegrityViolation_mapsTo400_withoutLeakingDbDetail() {
        // A real DataIntegrityViolation wraps a SQLException; 22001 = value too long.
        var ex = new DataIntegrityViolationException("could not execute statement",
                new java.sql.SQLException(
                        "value too long for type character varying(10)", "22001"));

        ResponseEntity<ApiResponse<Void>> resp = handler.handleDataIntegrity(ex);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo(400);
        // Non-blaming message — no raw SQL / column name leaked to the client.
        assertThat(resp.getBody().error()).doesNotContain("character varying");
        assertThat(resp.getBody().error()).contains("too long");
    }

    @Test
    void illegalArgument_mapsTo400() {
        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleBadInput(new IllegalArgumentException("bad enum"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().status()).isEqualTo(400);
    }
}
