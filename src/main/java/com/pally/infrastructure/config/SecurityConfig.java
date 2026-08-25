package com.pally.infrastructure.config;

import com.pally.infrastructure.auth.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    /**
     * Explicit CORS allow-list (env-overridable via {@code PALLY_CORS_ALLOWED_ORIGINS},
     * comma-separated). Patterns support wildcards (e.g. {@code https://*.vercel.app}
     * for preview deploys). Replaces the prior {@code "*"} wildcard.
     */
    private final List<String> allowedOrigins;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthFilter,
            @Value("${pally.cors.allowed-origins:"
                    + "https://apalchi.com,https://www.apalchi.com,"
                    + "http://localhost:3000,https://*.vercel.app}") List<String> allowedOrigins) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(cors -> cors.configurationSource(corsSource()))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Authentication required\",\"status\":401}");
                })
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/google",
                    "/api/v1/auth/apple",
                    "/api/v1/auth/forgot-password",
                    "/api/v1/auth/reset-password",
                    "/api/v1/auth/link/password",
                    "/api/v1/auth/link/request-code",
                    "/api/v1/auth/link/verify-code",
                    "/api/v1/auth/biometric/verify",
                    "/api/v1/auth/verify-email",
                    "/api/v1/account/restore",
                    "/api/v1/account/delete/request-by-email",
                    "/api/v1/account/delete/confirm",
                    "/api/v1/onboard/quick",
                    "/api/v1/subscription/webhook",
                    // Public tokenized trusted-adult review flow (no auth; no PII
                    // in responses). The owner-side create/revoke/list endpoints
                    // live under /api/v1/wiki-pages/** and stay authenticated.
                    "/api/v1/review/**",
                    // Parental consent approval — followed from a one-tap EMAIL link by
                    // the parent, who has no app session. Token-authenticated inside the
                    // handler (single-use, expiring), not by JWT. Must be public.
                    "/api/v1/consent/approve",
                    "/actuator/health",
                    "/actuator/info",
                    "/actuator/prometheus",
                    // Email-link-driven admin safety console — gated by X-Admin-Secret header,
                    // not JWT (no user session when following an email link).
                    "/api/v1/admin/safety-flags",
                    // Centre invite lookup — public so the accept-invite page can
                    // display the centre name before the user logs in.
                    "/api/v1/auth/invite/**",
                    // Lead-capture for demo requests — no auth required.
                    "/api/v1/demo-request",
                    // Forced-update gate — the app checks this on launch, before login.
                    "/api/v1/app/min-version",
                    // RevenueCat IAP webhook — server-to-server; auth'd by a shared
                    // Authorization secret inside the handler, not a user JWT.
                    "/api/v1/subscription/revenuecat-webhook"
                ).permitAll()
                // Admin endpoints — must be checked BEFORE the catch-all
                // authenticated() so /admin/** with a USER token is REJECTED
                // rather than treated as "any authenticated principal". This
                // closes the IDOR the audit flagged on /admin/chat-debug/{id}
                // et al.
                //
                // (Corrected 2026-08-25: this comment previously said the
                // rejection is a 403. Measured against a running server, a USER
                // token on /admin/leads returns 401 "Authentication required" —
                // the entry point answers before the access-denied handler. The
                // authorization outcome is right; only the documented status was
                // wrong, and CentreOnboardPrivilegeGateTest now pins the real one.)
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // Same gate on the centre-admin bootstrap endpoint that
                // creates organizations + promotes CENTRE_ADMIN — currently
                // env-secret-guarded but ADMIN role is the canonical check.
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/v1/centre/admin/**").hasRole("ADMIN")
                // PRIVILEGE GAP (closed 2026-08-25): /centre/onboard creates an
                // organization and makes the CALLER its owner, but it previously
                // fell through to .anyRequest().authenticated() — so ANY
                // authenticated principal could mint a centre and own it,
                // including a mobile student account. Self-serve centre creation
                // is exactly what the move to invite-only web access removes.
                //
                // ADMIN is an INTERIM gate. The intended long-term authorisation
                // is the centre-invite token (an invited owner creates their org
                // by accepting the invite), which does not exist yet — gating on
                // it today would reference a mechanism that isn't built. ADMIN is
                // also the gate already used for /admin/** and /centre/admin/**
                // directly above, so this follows the established pattern rather
                // than inventing a third one. Relax to the invite path when it
                // lands; do NOT relax it back to authenticated().
                //
                // Blast radius verified before shipping: pally (mobile) calls
                // only /centre/leave-class and /centre/redeem-class-code, never
                // this endpoint; and all 17 production orgs already have owners,
                // who never reach this path (onboard returns alreadyOwned).
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                        "/api/v1/centre/onboard").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Explicit allow-list (supports the *.vercel.app wildcard) instead of "*".
        // allowCredentials(true) lets the browser send the httpOnly auth cookie on
        // cross-origin API calls from apalchi.com; it's compatible with
        // setAllowedOriginPatterns (unlike "*") and additive for header-auth clients.
        config.setAllowedOriginPatterns(allowedOrigins);
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/actuator/**", config);
        return source;
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
