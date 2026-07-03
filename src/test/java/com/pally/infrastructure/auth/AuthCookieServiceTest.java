package com.pally.infrastructure.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AuthCookieServiceTest {

    @Test
    void setAuthCookie_isNoOp_whenNoDomainConfigured() {
        var svc = new AuthCookieService(""); // feature off (the default)
        var response = new MockHttpServletResponse();

        svc.setAuthCookie(response, "TOKEN");

        assertThat(svc.isEnabled()).isFalse();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void setAuthCookie_setsHardenedCookie_whenDomainConfigured() {
        var svc = new AuthCookieService(".apalchi.com");
        var response = new MockHttpServletResponse();

        svc.setAuthCookie(response, "TOKEN");

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie)
                .contains("auth_token=TOKEN")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax")
                .contains("Domain=.apalchi.com")
                .contains("Path=/")
                .contains("Max-Age=2592000"); // 30 days
    }

    @Test
    void readAuthCookie_returnsValue_whenPresent_elseNull() {
        var withCookie = new MockHttpServletRequest();
        withCookie.setCookies(new Cookie("auth_token", "V123"), new Cookie("other", "x"));
        assertThat(AuthCookieService.readAuthCookie(withCookie)).isEqualTo("V123");

        assertThat(AuthCookieService.readAuthCookie(new MockHttpServletRequest())).isNull();
    }
}
