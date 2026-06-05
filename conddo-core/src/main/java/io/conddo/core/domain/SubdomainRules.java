package io.conddo.core.domain;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Subdomain validity rules shared between the public-traffic resolver and the
 * tenant-facing claim/rename flow. RFC-1035 label-style (3–63 chars,
 * lowercase, digits, hyphens; no leading/trailing hyphen) plus a reserved
 * list of internal labels we route ourselves.
 *
 * <p>Kept here in {@code conddo-core} so the dashboard write path and the
 * inbound-request resolver can't drift on what counts as valid.
 */
public final class SubdomainRules {

    /** Internal labels that must never resolve to a tenant. */
    public static final Set<String> RESERVED = Set.of(
            "api", "app", "www", "admin", "staff", "studio", "jobs",
            "mail", "static", "cdn", "minio", "grafana", "auth");

    /** RFC-1035 style: 3–63 chars, [a-z0-9-], no leading/trailing hyphen. */
    private static final Pattern VALID = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])?$");

    private SubdomainRules() {
    }

    /** True when the candidate is well-formed and not reserved. */
    public static boolean isValid(String candidate) {
        if (candidate == null) {
            return false;
        }
        String s = candidate.trim().toLowerCase();
        if (s.length() < 3 || s.length() > 63) {
            return false;
        }
        if (RESERVED.contains(s)) {
            return false;
        }
        return VALID.matcher(s).matches();
    }

    /** Lower-cases + trims; returns null when the input is blank. */
    public static String normalise(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim().toLowerCase();
        return s.isEmpty() ? null : s;
    }
}
