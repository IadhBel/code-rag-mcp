package com.acme.coderag.mcp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Checks the bearer token on every request except health probes.
 *
 * <p>Hand-rolled rather than pulling in Spring Security: there is exactly one
 * credential and one decision, and a servlet filter makes that auditable at a
 * glance. If this ever grows to real authorisation -- per-repository scopes, JWT
 * validation, OAuth resource-server metadata -- replace it with
 * {@code spring-boot-starter-oauth2-resource-server} rather than extending it.
 */
@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenFilter.class);
    private static final String PREFIX = "Bearer ";

    private final SecurityProperties properties;
    private final byte[] expected;

    public BearerTokenFilter(SecurityProperties properties) {
        properties.validate();
        this.properties = properties;
        this.expected = properties.readToken() == null
                ? new byte[0]
                : properties.readToken().getBytes(StandardCharsets.UTF_8);
        if (!properties.enabled()) {
            log.warn("Application-level authentication is DISABLED. "
                    + "Acceptable locally; never in a deployed environment.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Cloud Run's health checks are unauthenticated by design.
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.enabled()) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(PREFIX) || !matches(header.substring(PREFIX.length()))) {
            // 401 with WWW-Authenticate is what an MCP client expects, and what
            // triggers its authentication flow rather than a bare failure.
            response.setHeader("WWW-Authenticate", "Bearer realm=\"code-rag\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid or missing bearer token");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Constant-time comparison: a timing oracle on a static token is worth avoiding. */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(expected, presented.trim().getBytes(StandardCharsets.UTF_8));
    }
}
