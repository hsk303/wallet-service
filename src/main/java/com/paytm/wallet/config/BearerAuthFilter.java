package com.paytm.wallet.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Deliberately minimal: the bearer token's value IS the caller's user id.
 * There is no separate user-registration step or signature/JWT validation.
 * Auth sophistication is explicitly out of scope for this exercise; this
 * filter exists only so POST /wallets has a caller identity to key the
 * get-or-create on, and so every request has *a* caller for logging.
 */
public class BearerAuthFilter extends OncePerRequestFilter {

    public static final String CALLER_ID_ATTRIBUTE = "callerId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/actuator")
            || path.equals("/swagger-ui.html")
            || path.startsWith("/swagger-ui/")
            || path.equals("/v3/api-docs")
            || path.startsWith("/v3/api-docs/")
            || path.startsWith("/webjars/")) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or malformed Authorization bearer token\"}");
            return;
        }

        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or malformed Authorization bearer token\"}");
            return;
        }

        request.setAttribute(CALLER_ID_ATTRIBUTE, token);
        chain.doFilter(request, response);
    }
}
