package io.conddo.core.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Hard-coded Nigerian state → delivery fee table for the public pharmacy
 * site (PHARMACY_PUBLIC_API_SPEC §11). Mirrors Seb&Bayor's reference
 * {@code lib/delivery-fees.ts} — Lagos same-day, neighbouring states
 * next-day, far states multi-day. The merchant cannot customize this
 * via the dashboard yet; per-tenant overrides land in a follow-up.
 */
@Service
public class PharmacyDeliveryFeeService {

    private record FeeRow(BigDecimal fee, String estimate) {
    }

    private static final FeeRow DEFAULT_ROW =
            new FeeRow(new BigDecimal("3500"), "3-5 business days");

    private static final Map<String, FeeRow> TABLE = Map.ofEntries(
            Map.entry("LAGOS",       new FeeRow(new BigDecimal("1500"), "2-4 hours (same day)")),
            Map.entry("OGUN",        new FeeRow(new BigDecimal("2000"), "Next business day")),
            Map.entry("OYO",         new FeeRow(new BigDecimal("2500"), "Next business day")),
            Map.entry("ABUJA",       new FeeRow(new BigDecimal("2500"), "1-2 business days")),
            Map.entry("FCT",         new FeeRow(new BigDecimal("2500"), "1-2 business days")),
            Map.entry("RIVERS",      new FeeRow(new BigDecimal("3000"), "2-3 business days")),
            Map.entry("KADUNA",      new FeeRow(new BigDecimal("3000"), "2-3 business days")),
            Map.entry("KANO",        new FeeRow(new BigDecimal("3500"), "2-4 business days")),
            Map.entry("ENUGU",       new FeeRow(new BigDecimal("3000"), "2-3 business days")),
            Map.entry("ANAMBRA",     new FeeRow(new BigDecimal("3000"), "2-3 business days")),
            Map.entry("DELTA",       new FeeRow(new BigDecimal("3000"), "2-3 business days")),
            Map.entry("EDO",         new FeeRow(new BigDecimal("2500"), "2 business days")));

    public Quote quote(String state) {
        String normalised = normalise(state);
        FeeRow row = TABLE.getOrDefault(normalised, DEFAULT_ROW);
        return new Quote(normalised, row.fee(), row.estimate());
    }

    private static String normalise(String state) {
        if (state == null) {
            return "";
        }
        return state.trim().toUpperCase(Locale.ROOT);
    }

    public record Quote(String state, BigDecimal fee, String estimate) {
    }
}
