package io.conddo.studio.builder;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The seven section types the builder catalogue ships with (§21.4). Each entry
 * lists the required top-level keys on its {@code content} JSONB; the optional
 * ones are documented in the spec but not enforced server-side (the FE handles
 * UX validation, server-side is just a safety net against malformed payloads).
 */
public final class SectionTypes {

    public static final String HERO = "HERO";
    public static final String SERVICES = "SERVICES";
    public static final String ABOUT = "ABOUT";
    public static final String CTA = "CTA";
    public static final String GALLERY = "GALLERY";
    public static final String CONTACT = "CONTACT";
    public static final String CUSTOM = "CUSTOM";

    public static final Set<String> ALL = Set.of(HERO, SERVICES, ABOUT, CTA, GALLERY, CONTACT, CUSTOM);

    /**
     * Required top-level content keys per type. CUSTOM is intentionally
     * unvalidated beyond size (the escape hatch).
     */
    public static final Map<String, List<String>> REQUIRED_KEYS = Map.of(
            HERO, List.of("headline"),
            SERVICES, List.of("heading", "services"),
            ABOUT, List.of("heading", "body"),
            CTA, List.of("headline", "primaryCta"),
            GALLERY, List.of("heading", "images"),
            CONTACT, List.of("heading"),
            CUSTOM, List.of("html"));

    private SectionTypes() {
    }
}
