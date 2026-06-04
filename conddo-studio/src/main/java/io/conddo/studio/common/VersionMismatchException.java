package io.conddo.studio.common;

/**
 * §21.3 — optimistic-lock check failed. The caller's {@code If-Match} header
 * (or implicit expected version) doesn't match the current {@code Site.version}.
 * Mapped to {@code 409 VERSION_MISMATCH} so the FE can fetch the latest state
 * and merge or retry.
 */
public class VersionMismatchException extends RuntimeException {
    private final int expected;
    private final int actual;

    public VersionMismatchException(int expected, int actual) {
        super("Expected version " + expected + " but server is at " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public int getExpected() {
        return expected;
    }

    public int getActual() {
        return actual;
    }
}
