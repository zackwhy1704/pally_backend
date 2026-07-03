package com.pally.api.auth;

import com.pally.domain.centre.CentreInviteService;
import com.pally.infrastructure.auth.AuthCookieService;
import com.pally.infrastructure.auth.AuthService;
import com.pally.infrastructure.auth.SocialTokenVerifier;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bearer→cookie bridge re-issues the httpOnly cookie from the SAME token that
 * authenticated the request (never a body token, never a fresh one), and is a no-op
 * when the cookie feature is off. (The 401-without-a-token case is enforced by the
 * security filter — the route is not permitAll — so it isn't exercised here.)
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerCookieBridgeTest {

    @Mock AuthService authService;
    @Mock CentreInviteService inviteService;
    @Mock SlidingWindowRateLimiter rateLimiter;
    @Mock SocialTokenVerifier socialTokenVerifier;

    private AuthController controller(String cookieDomain) {
        return new AuthController(authService, inviteService, rateLimiter,
                socialTokenVerifier, new AuthCookieService(cookieDomain));
    }

    @Test
    void bearer_withDomainConfigured_setsSameTokenCookie_204() {
        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer TOK.EN.123");
        var res = new MockHttpServletResponse();

        var result = controller(".apalchi.com").cookieBridge(req, res);

        assertThat(result.getStatusCode().value()).isEqualTo(204);
        String setCookie = res.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull()
                .contains("auth_token=TOK.EN.123") // the PRESENTED token, no fresh mint
                .contains("HttpOnly").contains("Secure")
                .contains("SameSite=Lax").contains("Domain=.apalchi.com");
    }

    @Test
    void domainUnset_isNoOp_204() {
        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer TOK");
        var res = new MockHttpServletResponse();

        var result = controller("").cookieBridge(req, res);

        assertThat(result.getStatusCode().value()).isEqualTo(204);
        assertThat(res.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void authenticatedViaExistingCookie_reissuesFromThatCookie_notFabricated() {
        var req = new MockHttpServletRequest(); // no bearer header
        req.setCookies(new Cookie("auth_token", "COOKIE.TOK"));
        var res = new MockHttpServletResponse();

        controller(".apalchi.com").cookieBridge(req, res);

        assertThat(res.getHeader(HttpHeaders.SET_COOKIE)).isNotNull().contains("auth_token=COOKIE.TOK");
    }

    @Test
    void noBearerNoCookie_setsNothing() {
        var req = new MockHttpServletRequest(); // nothing to bridge
        var res = new MockHttpServletResponse();

        controller(".apalchi.com").cookieBridge(req, res);

        assertThat(res.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }
}
