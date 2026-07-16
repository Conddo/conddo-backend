package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Tenant;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantScoped;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/**
 * Tenant-facing brand read/write. The four fields — logo URL, primary
 * colour, secondary colour, font pairing — live on the {@code tenants}
 * row (V14 + V68), so this service is a thin facade over the tenant
 * lookup + column mutation.
 *
 * <p>Kept separate from {@link SettingsService} because the brand is the
 * one settings surface that gets read on every published-website request
 * — coupling it to the wider settings CRUD would make cache invalidation
 * harder as the site renderer scales.
 */
@Service
public class BrandService {

    /** Sensible defaults used when the tenant hasn't set values yet. Mirror
     *  the V68 backfill so a freshly-created tenant with no explicit brand
     *  still renders a coherent site. */
    public static final String DEFAULT_PRIMARY_COLOR = "#785DCD";
    public static final String DEFAULT_SECONDARY_COLOR = "#111111";
    public static final String DEFAULT_FONT_PAIRING = "inter";

    /** Accept 3-, 4-, 6-, or 8-digit hex colors (with the leading #).
     *  Rejects "rgb()" / "hsl()" / colour names — every renderer we ship
     *  can bind to a hex literal, and other formats are the leading edge
     *  of a "why does my logo look grey on iOS" support ticket. */
    private static final Pattern HEX_COLOR = Pattern.compile("^#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");
    /** Whitelist matching the FE font picker labels. Extend with the
     *  BrandSettings picker rather than accepting arbitrary strings —
     *  arbitrary values would either 404 in the FE map or render an
     *  ugly system fallback. */
    private static final java.util.Set<String> ALLOWED_FONTS = java.util.Set.of(
            "inter", "modern", "classic", "bold", "minimal");

    private final TenantRepository tenantRepository;

    public BrandService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public Brand current() {
        return read(loadOrFail());
    }

    @TenantScoped
    @Transactional
    public Brand update(BrandPatch patch) {
        Tenant tenant = loadOrFail();
        if (patch.logoUrl() != null) {
            tenant.setLogoUrl(patch.logoUrl().isBlank() ? null : patch.logoUrl().trim());
        }
        if (patch.primaryColor() != null) {
            tenant.setPrimaryColor(requireValidHex(patch.primaryColor(), "primaryColor"));
        }
        if (patch.secondaryColor() != null) {
            tenant.setSecondaryColor(requireValidHex(patch.secondaryColor(), "secondaryColor"));
        }
        if (patch.fontPairing() != null) {
            tenant.setFontPairing(requireAllowedFont(patch.fontPairing()));
        }
        return read(tenantRepository.save(tenant));
    }

    // ----- internals -------------------------------------------------------

    private Tenant loadOrFail() {
        return tenantRepository.findById(TenantContext.require())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));
    }

    private Brand read(Tenant t) {
        return new Brand(
                t.getLogoUrl(),
                orDefault(t.getPrimaryColor(), DEFAULT_PRIMARY_COLOR),
                orDefault(t.getSecondaryColor(), DEFAULT_SECONDARY_COLOR),
                orDefault(t.getFontPairing(), DEFAULT_FONT_PAIRING));
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String requireValidHex(String value, String field) {
        String trimmed = value.trim();
        if (!HEX_COLOR.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a hex color like #785DCD, got '" + value + "'");
        }
        return trimmed;
    }

    private static String requireAllowedFont(String value) {
        String normalised = value.trim().toLowerCase();
        if (!ALLOWED_FONTS.contains(normalised)) {
            throw new IllegalArgumentException(
                    "fontPairing must be one of " + ALLOWED_FONTS + ", got '" + value + "'");
        }
        return normalised;
    }

    // ----- wire shapes -----------------------------------------------------

    public record Brand(String logoUrl, String primaryColor, String secondaryColor, String fontPairing) {}

    public record BrandPatch(String logoUrl, String primaryColor, String secondaryColor, String fontPairing) {}
}
