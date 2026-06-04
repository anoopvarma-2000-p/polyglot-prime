package org.techbd.csv.config;

import java.io.IOException;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Validates the HTTP Host header against TECHBD_ALLOWED_HOSTS to prevent
 * Host Header Injection (CWE-113). Runs before any controller logic.
 *
 * Health-check endpoints (/, /actuator/health) and ALB health checks to
 * /metadata are exempt so that infrastructure probes are not blocked.
 *
 * Set TECHBD_ALLOWED_HOSTS to a comma-separated list of permitted hostnames
 * (without port), e.g. "synthetic.csv.stage.techbd.org,localhost".
 * When the env var is absent, the filter is disabled (fail-open) so that
 * deployments without the variable configured are not broken.
 */
@Component
@Order(-999)
public class HostHeaderValidationFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(HostHeaderValidationFilter.class);

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {

        String allowedHostsEnv = System.getenv("TECHBD_ALLOWED_HOSTS");
        if (allowedHostsEnv == null || allowedHostsEnv.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();

        // Exempt root and actuator health probes
        if (uri.equals("/") || uri.startsWith("/actuator/health")) {
            chain.doFilter(request, response);
            return;
        }

        // Exempt ALB health checks to /metadata (identified by User-Agent)
        String userAgent = request.getHeader("User-Agent");
        if (uri.contains("/metadata") && userAgent != null && userAgent.startsWith("ELB-HealthChecker")) {
            chain.doFilter(request, response);
            return;
        }

        String rawHost = request.getHeader("Host");
        // Strip port so "host.example.com:443" matches "host.example.com" in the list
        String hostname = rawHost != null ? rawHost.split(":")[0].trim() : "";

        boolean allowed = Arrays.stream(allowedHostsEnv.split(","))
                .map(String::trim)
                .anyMatch(hostname::equals);

        if (!allowed) {
            LOG.warn("HostHeaderValidationFilter: rejected request with invalid Host '{}' uri={}", rawHost, uri);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Bad Request\",\"description\":\"Invalid Host header\"}");
            response.getWriter().flush();
            return;
        }

        chain.doFilter(request, response);
    }
}
