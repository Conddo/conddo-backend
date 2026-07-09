package io.conddo.api.publicapi;

import io.conddo.core.domain.Property;
import io.conddo.core.repository.PropertyRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public catalog reads for the merchant's real-estate website. Gated by the
 * {@code X-Conddo-Site-Key} header via {@link PublicSiteInterceptor}, which
 * binds the tenant before any of these methods run — RLS then scopes every
 * read to the merchant's own listings.
 *
 * <p>Only {@code public = true} + {@code status = available} rows are
 * returned; drafts, sold, and rented properties stay in the dashboard.
 *
 * <p>Vertical parity with {@code PublicPharmacyCatalogController} — same
 * response shape (products list + pagination wrapper) so a single generic
 * FE client can consume both.
 */
@RestController
@RequestMapping("/api/v1/public/{slug}/real-estate")
public class PublicRealEstateCatalogController {

    private final PropertyRepository properties;

    public PublicRealEstateCatalogController(PropertyRepository properties) {
        this.properties = properties;
    }

    @GetMapping("/properties")
    @Transactional(readOnly = true)
    public Map<String, Object> listProperties(
            @RequestParam(required = false) String listingType,
            @RequestParam(required = false) String propertyType,
            @RequestParam(required = false) Boolean featured) {
        List<Property> rows = properties.findPublicAvailable();
        // In-memory filtering keeps this endpoint self-contained. The list is
        // capped by the query (findPublicAvailable orders featured-first, then
        // newest); most tenants have <500 properties so this is cheap.
        List<Property> filtered = rows.stream()
                .filter(p -> listingType == null || listingType.equalsIgnoreCase(p.getListingType()))
                .filter(p -> propertyType == null || propertyType.equalsIgnoreCase(p.getPropertyType()))
                .filter(p -> featured == null || p.isFeatured() == featured)
                .toList();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Property p : filtered) {
            out.add(toPropertyDto(p, false));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("properties", out);
        resp.put("count", out.size());
        return resp;
    }

    @GetMapping("/properties/{propertySlug}")
    @Transactional(readOnly = true)
    public Map<String, Object> propertyDetail(@PathVariable String propertySlug) {
        Property p = properties.findBySlugAndIsPublicTrue(propertySlug)
                .orElseThrow(() -> new PublicNotFoundException("Property not found"));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("property", toPropertyDto(p, true));
        return resp;
    }

    // ----- DTO --------------------------------------------------------------

    private static Map<String, Object> toPropertyDto(Property p, boolean detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.getId());
        row.put("title", p.getTitle());
        row.put("slug", p.getSlug());
        row.put("referenceCode", p.getReferenceCode());
        row.put("propertyType", p.getPropertyType());
        row.put("listingType", p.getListingType());
        row.put("price", p.getPrice());
        row.put("currency", p.getCurrency());
        row.put("priceNegotiable", p.isPriceNegotiable());
        row.put("rentPeriod", p.getRentPeriod());
        row.put("bedrooms", p.getBedrooms());
        row.put("bathrooms", p.getBathrooms());
        row.put("sizeSqm", p.getSizeSqm());
        row.put("estateName", p.getEstateName());
        row.put("state", p.getState());
        row.put("landmark", p.getLandmark());
        row.put("featured", p.isFeatured());
        // Primary hero + full gallery — thumbnail path uses primary only,
        // detail returns the whole set.
        List<String> images = p.getImages() != null ? p.getImages() : List.of();
        row.put("primaryImageUrl", images.isEmpty() ? null : images.get(0));
        if (detail) {
            row.put("images", images);
            row.put("addressLine", p.getAddressLine());
            row.put("lga", p.getLga());
            row.put("country", p.getCountry());
            row.put("latitude", p.getLatitude());
            row.put("longitude", p.getLongitude());
            row.put("toilets", p.getToilets());
            row.put("plotSizeSqm", p.getPlotSizeSqm());
            row.put("yearBuilt", p.getYearBuilt());
            row.put("parkingSpaces", p.getParkingSpaces());
            row.put("features", p.getFeatures());
            row.put("floorPlanUrl", p.getFloorPlanUrl());
            row.put("virtualTourUrl", p.getVirtualTourUrl());
            row.put("description", p.getDescription());
            Map<String, Boolean> docs = new LinkedHashMap<>();
            docs.put("cOfO", p.isHasCofO());
            docs.put("deedOfAssignment", p.isHasDeedOfAssignment());
            docs.put("surveyPlan", p.isHasSurveyPlan());
            docs.put("governorConsent", p.isHasGovernorConsent());
            docs.put("gazette", p.isHasGazette());
            row.put("documents", docs);
            row.put("documentNotes", p.getDocumentNotes());
        }
        return row;
    }

    /** 404 with a public-safe message — no stack trace, no internal detail. */
    static class PublicNotFoundException extends RuntimeException {
        PublicNotFoundException(String message) {
            super(message);
        }
    }
}
