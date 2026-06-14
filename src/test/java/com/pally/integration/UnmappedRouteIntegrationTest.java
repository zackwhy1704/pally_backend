package com.pally.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue 4: an unmapped API route must return a clean 404 in the standard
 * {@code ApiResponse.error} envelope (not the generic 500 it used to fall
 * through to). Authenticated so the request gets past Spring Security to the
 * DispatcherServlet where NoHandlerFoundException is raised.
 */
class UnmappedRouteIntegrationTest extends IntegrationTestBase {

    @Test
    void unmappedApiRoute_returns404_inStandardEnvelope_notpath500() {
        AuthResult auth = registerUser(
                "unmapped-" + System.nanoTime() + "@test.com", "password123");

        ResponseEntity<Map> resp = get("/api/v1/this-route-does-not-exist", auth.token());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("status")).isEqualTo(404);
        assertThat(resp.getBody().get("error").toString()).contains("No endpoint");
    }
}
