package io.conddo.core.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for the STAFF sub-role × module access
 * matrix (HANDOFF_2026-06-12 §4). Lives in conddo-core so both the
 * Spring Security guard ({@code StaffAccess} in conddo-api) and the
 * catalogue endpoint ({@code StaffService.roles()}) can read from
 * the same data — no risk of the BE 403 and the FE nav drifting.
 *
 * <p>FE consumes this via {@code GET /api/v1/staff/roles}'s
 * {@code moduleAccess} field. Omitted modules default to NONE
 * (HANDOFF_2026-06-12 reply, Q1).
 */
public final class StaffAccessMatrix {

    public enum Permission {
        NONE, READ, WRITE;

        public String wire() {
            return name().toLowerCase();
        }

        public boolean reads() {
            return this != NONE;
        }

        public boolean writes() {
            return this == WRITE;
        }
    }

    private static final Map<String, Map<String, Permission>> MATRIX;

    static {
        Map<String, Map<String, Permission>> m = new LinkedHashMap<>();
        m.put("MANAGER", Map.ofEntries(
                Map.entry("inventory", Permission.WRITE),
                Map.entry("orders", Permission.WRITE),
                Map.entry("payments", Permission.WRITE),
                Map.entry("customers", Permission.WRITE),
                Map.entry("analytics", Permission.WRITE),
                Map.entry("prescriptions", Permission.WRITE),
                Map.entry("consultations", Permission.WRITE),
                Map.entry("pos", Permission.WRITE),
                Map.entry("emr", Permission.WRITE),
                Map.entry("marketing", Permission.WRITE),
                Map.entry("followups", Permission.WRITE),
                Map.entry("loyalty", Permission.WRITE),
                Map.entry("staff", Permission.NONE),
                Map.entry("billing", Permission.NONE)));
        m.put("PHARMACIST", Map.ofEntries(
                Map.entry("inventory", Permission.READ),
                Map.entry("orders", Permission.READ),
                Map.entry("customers", Permission.READ),
                Map.entry("analytics", Permission.READ),
                Map.entry("prescriptions", Permission.WRITE),
                Map.entry("consultations", Permission.WRITE),
                Map.entry("emr", Permission.WRITE),
                Map.entry("followups", Permission.WRITE),
                Map.entry("pos", Permission.READ),
                Map.entry("loyalty", Permission.READ),
                Map.entry("payments", Permission.NONE),
                Map.entry("marketing", Permission.NONE),
                Map.entry("staff", Permission.NONE),
                Map.entry("billing", Permission.NONE)));
        m.put("CASHIER", Map.ofEntries(
                Map.entry("pos", Permission.WRITE),
                Map.entry("customers", Permission.READ),
                Map.entry("inventory", Permission.READ),
                Map.entry("orders", Permission.READ),
                Map.entry("payments", Permission.READ),
                Map.entry("prescriptions", Permission.READ),
                Map.entry("loyalty", Permission.READ),
                Map.entry("analytics", Permission.NONE),
                Map.entry("consultations", Permission.NONE),
                Map.entry("emr", Permission.NONE),
                Map.entry("followups", Permission.NONE),
                Map.entry("marketing", Permission.NONE),
                Map.entry("staff", Permission.NONE),
                Map.entry("billing", Permission.NONE)));
        m.put("STOCK_MANAGER", Map.ofEntries(
                Map.entry("inventory", Permission.WRITE),
                Map.entry("analytics", Permission.READ),
                Map.entry("orders", Permission.READ),
                Map.entry("customers", Permission.READ),
                Map.entry("payments", Permission.NONE),
                Map.entry("pos", Permission.NONE),
                Map.entry("prescriptions", Permission.NONE),
                Map.entry("consultations", Permission.NONE),
                Map.entry("emr", Permission.NONE),
                Map.entry("followups", Permission.NONE),
                Map.entry("loyalty", Permission.NONE),
                Map.entry("marketing", Permission.NONE),
                Map.entry("staff", Permission.NONE),
                Map.entry("billing", Permission.NONE)));
        m.put("BOOKKEEPER", Map.ofEntries(
                Map.entry("orders", Permission.READ),
                Map.entry("payments", Permission.READ),
                Map.entry("analytics", Permission.READ),
                Map.entry("customers", Permission.READ),
                Map.entry("inventory", Permission.NONE),
                Map.entry("pos", Permission.NONE),
                Map.entry("prescriptions", Permission.NONE),
                Map.entry("consultations", Permission.NONE),
                Map.entry("emr", Permission.NONE),
                Map.entry("followups", Permission.NONE),
                Map.entry("loyalty", Permission.NONE),
                Map.entry("marketing", Permission.NONE),
                Map.entry("staff", Permission.NONE),
                Map.entry("billing", Permission.NONE)));
        MATRIX = Collections.unmodifiableMap(m);
    }

    private StaffAccessMatrix() {
    }

    public static Permission permissionFor(String staffRole, String module) {
        if (staffRole == null || module == null) {
            return Permission.NONE;
        }
        Map<String, Permission> row = MATRIX.get(staffRole);
        if (row == null) {
            return Permission.NONE;
        }
        return row.getOrDefault(module, Permission.NONE);
    }

    /**
     * The machine-readable access map for a sub-role — module →
     * {@code "read" | "write"}. NONE entries are dropped (per the FE
     * reply: omitted modules default to none, smaller wire shape).
     * Returns an empty map for unknown sub-roles.
     */
    public static Map<String, String> modulesFor(String staffRole) {
        Map<String, Permission> row = MATRIX.get(staffRole);
        if (row == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Permission> e : row.entrySet()) {
            if (e.getValue() != Permission.NONE) {
                out.put(e.getKey(), e.getValue().wire());
            }
        }
        return out;
    }
}
