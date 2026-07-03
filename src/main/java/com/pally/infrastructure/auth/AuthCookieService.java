package com.pally.infrastructure.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Issues + reads the web auth cookie (the httpOnly alternative to the localStorage
 * JWT). FEATURE-GATED on {@code auth.cookie.domain} (env AUTH_COOKIE_DOMAIN): when
 * blank (the default), {@link #setAuthCookie} is a no-op, so shipping this is a
 * zero-behaviour change until the same-site subdomain is live and the env is set.
 *
 * <p>The cookie is {@code HttpOnly; Secure; SameSite=Lax; Domain=<configured>} — it
 * only works when the API is same-site with the web (api.apalchi.com ↔ apalchi.com);
 * SameSite=Lax also means it is NOT sent on cross-site POSTs, which closes the CSRF
 * vector for state-changing calls. The mobile app is untouched: it keeps reading the
 * token from the response BODY and sending it as a Bearer header.
 */
@Component
public class AuthCookieService {

    public static final String COOKIE_NAME = "auth_token";

    /** Matches the JWT's own 30-day validity so the cookie doesn't outlive the token. */
    private static final Duration MAX_AGE = Duration.ofDays(30);

    private final String cookieDomain;

    public AuthCookieService(@Value("${auth.cookie.domain:}") String cookieDomain) {
        this.cookieDomain = cookieDomain;
    }

    /** True when the cookie feature is enabled (a domain is configured). */
    public boolean isEnabled() {
        return cookieDomain != null && !cookieDomain.isBlank();
    }

    /** Sets the httpOnly auth cookie — no-op when no cookie domain is configured. */
    public void setAuthCookie(HttpServletResponse response, String token) {
        if (!isEnabled()) {
            return; // feature-gated off → keep body-token-only behaviour
        }
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .domain(cookieDomain)
                .path("/")
                .maxAge(MAX_AGE)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** Reads the auth cookie value from the request, or null if absent. */
    public static String readAuthCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
