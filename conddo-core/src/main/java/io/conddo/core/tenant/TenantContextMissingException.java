package io.conddo.core.tenant;

/**
 * Thrown when an operation that requires a tenant runs without one in context.
 * Surfaced to clients as HTTP 400 (see GlobalExceptionHandler).
 */
public class TenantContextMissingException extends RuntimeException {

    public TenantContextMissingException() {
        super("No tenant in context. Provide a valid X-Tenant-Id.");
    }
}
