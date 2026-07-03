package com.pally.infrastructure.auth;

import com.pally.infrastructure.persistence.auth.RevokedTokenJpaRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The filter authenticates from the Bearer header (mobile + legacy web) OR, when no
 * header is present, from the auth_token cookie (web cookie-auth). Header wins.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtAuthenticationFilterTest {

    @Mock JwtService jwtService;
    @Mock RevokedTokenJpaRepository revokedTokenRepo;
    @Mock FilterChain chain;

    private JwtAuthenticationFilter filter() {
        return new JwtAuthenticationFilter(jwtService, revokedTokenRepo);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void stubValidToken(String token, String userId) {
        when(jwtService.extractUserId(token)).thenReturn(userId);
        when(jwtService.extractJti(token)).thenReturn("jti-" + token);
        when(jwtService.extractRole(token)).thenReturn("USER");
        when(revokedTokenRepo.existsById("jti-" + token)).thenReturn(false);
    }

    private String authenticatedPrincipal() {
        var a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : (String) a.getPrincipal();
    }

    @Test
    void authenticatesFromCookie_whenNoAuthorizationHeader() throws Exception {
        stubValidToken("CTOK", "cookie-user");
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("auth_token", "CTOK"));
        var response = new MockHttpServletResponse();

        filter().doFilterInternal(request, response, chain);

        assertThat(authenticatedPrincipal()).isEqualTo("cookie-user");
        verify(chain).doFilter(request, response);
    }

    @Test
    void headerTakesPrecedence_overCookie() throws Exception {
        stubValidToken("HTOK", "header-user");
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer HTOK");
        request.setCookies(new Cookie("auth_token", "CTOK")); // should be ignored

        filter().doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(authenticatedPrincipal()).isEqualTo("header-user");
    }

    @Test
    void bearerOnly_stillAuthenticates_mobilePathUnchanged() throws Exception {
        stubValidToken("MTOK", "mobile-user");
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer MTOK"); // no cookie at all

        filter().doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(authenticatedPrincipal()).isEqualTo("mobile-user");
    }

    @Test
    void noHeaderNoCookie_leavesUnauthenticated_butContinuesChain() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        filter().doFilterInternal(request, response, chain);

        assertThat(authenticatedPrincipal()).isNull();
        verify(chain).doFilter(request, response);
    }
}
