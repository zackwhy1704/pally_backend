package com.pally.infrastructure.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Locks in P1's role-claim contract so a future refactor can't
/// silently drop the ADMIN check on the JWT.
class JwtServiceTest {

    private final JwtService jwt = new JwtService(
            "test-secret-must-be-at-least-32-chars-long-xx");

    @Test
    void generateToken_legacy_defaultsRoleToUser() {
        String token = jwt.generateToken("user-1");
        assertThat(jwt.extractRole(token)).isEqualTo("USER");
    }

    @Test
    void generateToken_withRole_roundtripsClaim() {
        String token = jwt.generateToken("user-2", "ADMIN");
        assertThat(jwt.extractUserId(token)).isEqualTo("user-2");
        assertThat(jwt.extractRole(token)).isEqualTo("ADMIN");
    }

    @Test
    void generateToken_withNullRole_fallsBackToDefault() {
        String token = jwt.generateToken("user-3", null);
        assertThat(jwt.extractRole(token)).isEqualTo("USER");
    }

    @Test
    void generateToken_withBlankRole_fallsBackToDefault() {
        String token = jwt.generateToken("user-4", "   ");
        assertThat(jwt.extractRole(token)).isEqualTo("USER");
    }
}
