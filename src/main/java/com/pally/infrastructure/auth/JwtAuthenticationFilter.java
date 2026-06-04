package com.pally.infrastructure.auth;

import com.pally.infrastructure.persistence.auth.RevokedTokenJpaRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final RevokedTokenJpaRepository revokedTokenRepo;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        try {
            String userId = jwtService.extractUserId(token);

            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // Revocation check: tokens issued before account deletion must be
                // rejected immediately, even within their remaining validity window.
                // Tokens without a jti (minted before V55) are non-revocable —
                // log a warning but allow through for backward compatibility.
                String jti = jwtService.extractJti(token);
                if (jti == null) {
                    log.debug("[JWT] Token for user={} has no jti — cannot revoke; treating as valid", userId);
                } else if (revokedTokenRepo.existsById(jti)) {
                    log.warn("[JWT] Rejected revoked token jti={} user={}", jti, userId);
                    filterChain.doFilter(request, response);
                    return;
                }

                // Grant ROLE_<role> from the JWT claim so hasRole('ADMIN')
                // in SecurityConfig fires correctly. Defaults to USER for
                // legacy tokens minted before V36 — fail-closed because
                // `anyRequest().authenticated()` still demands a valid token.
                String role = jwtService.extractRole(token);
                var authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + role));
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        } catch (JwtException e) {
            log.debug("[JWT] Invalid token: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
