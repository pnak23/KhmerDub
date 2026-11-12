package com.khmerdub.api.security;

import com.khmerdub.api.config.KhmerDubProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Local-only hardening. The API binds to 127.0.0.1, but a web page open in the user's browser can still
 * try to reach it, by sending requests across sites or through DNS rebinding. This filter rejects:
 * <ul>
 *   <li>requests whose Host header is not in {@code khmerdub.allowed-hosts} (DNS rebinding);</li>
 *   <li>requests with an Origin header that is not in {@code khmerdub.allowed-origins} (cross-site requests).</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalRequestGuardFilter extends OncePerRequestFilter {

    private final Set<String> allowedHosts;
    private final Set<String> internalAllowedHosts;
    private final Set<String> allowedOrigins;

    public LocalRequestGuardFilter(KhmerDubProperties props) {
        this.allowedHosts = lower(props.allowedHosts());
        this.internalAllowedHosts = props.internalAllowedHosts() == null ? Set.of() : lower(props.internalAllowedHosts());
        this.allowedOrigins = Set.copyOf(props.allowedOrigins());
    }

    private static Set<String> lower(List<String> hosts) {
        return hosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getServerName() == null ? null : request.getServerName().toLowerCase(Locale.ROOT);
        boolean internalPath = request.getRequestURI().startsWith("/internal/");
        boolean hostOk = host != null
                && (allowedHosts.contains(host) || (internalPath && internalAllowedHosts.contains(host)));
        if (!hostOk) {
            reject(response, "Host not allowed");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            reject(response, "Origin not allowed");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"" + detail + "\"}");
    }
}
