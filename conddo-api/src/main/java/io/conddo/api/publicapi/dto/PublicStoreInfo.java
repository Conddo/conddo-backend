package io.conddo.api.publicapi.dto;

/** Public store-info shape (WEBSITE_INTEGRATION_SPEC §3). All public-safe fields. */
public record PublicStoreInfo(
        String name,
        String tagline,
        String logoUrl,
        String address,
        String city,
        String state,
        String phone,
        String email,
        String hours,
        Social social) {

    public record Social(String instagram, String facebook, String twitter, String linkedin) {
    }
}
