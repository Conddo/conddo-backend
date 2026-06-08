package io.conddo.api.publicapi;

import io.conddo.core.auth.CustomerJwtService;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;
import java.util.UUID;

/**
 * Helper for extracting the authenticated customer's id from the
 * {@code Authorization: Bearer ...} header on public-website endpoints
 * (PHARMACY_PUBLIC_API_SPEC §2). Controllers throw
 * {@link UnauthenticatedCustomerException} when the token is missing or
 * invalid; the global exception handler maps that to 401
 * {@code UNAUTHENTICATED}.
 */
public final class PublicCustomerAuth {

    private PublicCustomerAuth() {
    }

    public static UUID requireCustomerId(HttpServletRequest request,
                                         CustomerJwtService jwtService) {
        return resolve(request, jwtService).orElseThrow(
                () -> new UnauthenticatedCustomerException(
                        "Missing or invalid customer JWT — log in and retry."));
    }

    public static Optional<UUID> resolve(HttpServletRequest request,
                                         CustomerJwtService jwtService) {
        String header = request == null ? null : request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = header.substring("Bearer ".length()).trim();
        return jwtService.verify(token).map(CustomerJwtService::customerId);
    }

    public static class UnauthenticatedCustomerException extends RuntimeException {
        public UnauthenticatedCustomerException(String msg) {
            super(msg);
        }
    }
}
