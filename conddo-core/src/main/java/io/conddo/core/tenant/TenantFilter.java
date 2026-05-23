package io.conddo.core.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Resolves the tenant for each request and places it on {@link TenantContext}
 * for the duration of the request, clearing it afterwards.
 *
 * <p>Phase 0: tenant comes from the {@code X-Tenant-Id} header. This will be
 * replaced by subdomain → tenant resolution (Redis cache, PRD §6.3) and the
 * authenticated JWT's tenant claim. Requests without the header simply carry
 * no tenant; tenant-scoped operations then fail closed.
 */
public class TenantFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader(TENANT_HEADER);
            if (header != null && !header.isBlank()) {
                try {
                    TenantContext.set(UUID.fromString(header.trim()));
                } catch (IllegalArgumentException ex) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                            "Invalid " + TENANT_HEADER + " header (must be a UUID)");
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
