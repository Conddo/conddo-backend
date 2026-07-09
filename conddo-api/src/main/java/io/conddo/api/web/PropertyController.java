package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Property;
import io.conddo.core.repository.PropertyRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real estate vertical — properties module. Tenant-scoped via RLS.
 * Reads open to any staff role; writes default to owner + manager tier.
 */
@RestController
@RequestMapping("/api/v1/properties")
public class PropertyController {

    private static final String READ = "@staffAccess.canRead('properties')";
    private static final String WRITE = "@staffAccess.canWrite('properties')";

    private final PropertyRepository repository;
    private final TenantSession tenantSession;

    public PropertyController(PropertyRepository repository, TenantSession tenantSession) {
        this.repository = repository;
        this.tenantSession = tenantSession;
    }

    @GetMapping
    @PreAuthorize(READ)
    @Transactional(readOnly = true)
    public ApiResponse<PageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        tenantSession.bind();
        Page<Property> result = repository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
        List<PropertyRow> rows = result.getContent().stream().map(PropertyRow::from).toList();
        return ApiResponse.ok(new PageResponse(rows, result.getNumber(), result.getSize(),
                result.getTotalElements()));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    @Transactional(readOnly = true)
    public ApiResponse<PropertyDetail> get(@PathVariable UUID id) {
        tenantSession.bind();
        Property property = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Property not found"));
        return ApiResponse.ok(PropertyDetail.from(property));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @Transactional
    public ResponseEntity<ApiResponse<PropertyDetail>> create(@Valid @RequestBody CreatePropertyRequest req) {
        tenantSession.bind();
        Property property = new Property(TenantContext.require(), req.title(),
                req.propertyType(), req.listingType(), req.price());
        req.applyTo(property);
        Property saved = repository.save(property);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(PropertyDetail.from(saved)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    public ApiResponse<PropertyDetail> update(@PathVariable UUID id,
                                              @Valid @RequestBody UpdatePropertyRequest req) {
        tenantSession.bind();
        Property property = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Property not found"));
        req.applyTo(property);
        return ApiResponse.ok(PropertyDetail.from(repository.save(property)));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(WRITE)
    @Transactional
    public ApiResponse<PropertyDetail> changeStatus(@PathVariable UUID id,
                                                     @Valid @RequestBody StatusChange req) {
        tenantSession.bind();
        Property property = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Property not found"));
        property.changeStatus(req.status());
        return ApiResponse.ok(PropertyDetail.from(repository.save(property)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        tenantSession.bind();
        if (!repository.existsById(id)) {
            throw new NotFoundException("Property not found");
        }
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ----- DTOs -------------------------------------------------------------

    public record PageResponse(List<PropertyRow> content, int page, int size, long total) {
    }

    public record PropertyRow(UUID id, String title, String propertyType, String listingType,
                              String status, BigDecimal price, String currency,
                              String estateName, String state, Integer bedrooms,
                              String primaryImageUrl, boolean featured) {
        static PropertyRow from(Property p) {
            String primary = (p.getImages() != null && !p.getImages().isEmpty())
                    ? p.getImages().get(0) : null;
            return new PropertyRow(p.getId(), p.getTitle(), p.getPropertyType(), p.getListingType(),
                    p.getStatus(), p.getPrice(), p.getCurrency(),
                    p.getEstateName(), p.getState(), p.getBedrooms(),
                    primary, p.isFeatured());
        }
    }

    /** Detail view — full row for edit + preview surfaces. */
    public record PropertyDetail(UUID id, String title, String slug, String referenceCode,
                                 String propertyType, String listingType, String status,
                                 BigDecimal price, String currency, boolean priceNegotiable,
                                 String rentPeriod, String addressLine, String estateName,
                                 String lga, String state, String country, String landmark,
                                 BigDecimal latitude, BigDecimal longitude,
                                 Integer bedrooms, Integer bathrooms, Integer toilets,
                                 BigDecimal sizeSqm, BigDecimal plotSizeSqm, Integer yearBuilt,
                                 Integer parkingSpaces, List<String> features, List<String> images,
                                 String floorPlanUrl, String virtualTourUrl,
                                 Map<String, Boolean> documents, String documentNotes,
                                 boolean isPublic, boolean featured, String description) {
        static PropertyDetail from(Property p) {
            Map<String, Boolean> docs = Map.of(
                    "cOfO", p.isHasCofO(),
                    "deedOfAssignment", p.isHasDeedOfAssignment(),
                    "surveyPlan", p.isHasSurveyPlan(),
                    "governorConsent", p.isHasGovernorConsent(),
                    "gazette", p.isHasGazette()
            );
            return new PropertyDetail(p.getId(), p.getTitle(), p.getSlug(), p.getReferenceCode(),
                    p.getPropertyType(), p.getListingType(), p.getStatus(),
                    p.getPrice(), p.getCurrency(), p.isPriceNegotiable(),
                    p.getRentPeriod(), p.getAddressLine(), p.getEstateName(),
                    p.getLga(), p.getState(), p.getCountry(), p.getLandmark(),
                    p.getLatitude(), p.getLongitude(),
                    p.getBedrooms(), p.getBathrooms(), p.getToilets(),
                    p.getSizeSqm(), p.getPlotSizeSqm(), p.getYearBuilt(),
                    p.getParkingSpaces(), p.getFeatures(), p.getImages(),
                    p.getFloorPlanUrl(), p.getVirtualTourUrl(),
                    docs, p.getDocumentNotes(),
                    p.isPublic(), p.isFeatured(), p.getDescription());
        }
    }

    public record CreatePropertyRequest(
            @NotBlank String title,
            @NotBlank String propertyType,
            @NotBlank String listingType,
            @NotNull BigDecimal price,
            String slug,
            String currency,
            String rentPeriod,
            String addressLine,
            String estateName,
            String lga,
            String state,
            String landmark,
            Integer bedrooms,
            Integer bathrooms,
            BigDecimal sizeSqm,
            List<String> features,
            List<String> images,
            String description
    ) {
        void applyTo(Property p) {
            if (slug != null) p.setSlug(slug);
            if (currency != null) p.setCurrency(currency);
            if (rentPeriod != null) p.setRentPeriod(rentPeriod);
            if (addressLine != null) p.setAddressLine(addressLine);
            if (estateName != null) p.setEstateName(estateName);
            if (lga != null) p.setLga(lga);
            if (state != null) p.setState(state);
            if (landmark != null) p.setLandmark(landmark);
            if (bedrooms != null) p.setBedrooms(bedrooms);
            if (bathrooms != null) p.setBathrooms(bathrooms);
            if (sizeSqm != null) p.setSizeSqm(sizeSqm);
            if (features != null) p.setFeatures(features);
            if (images != null) p.setImages(images);
            if (description != null) p.setDescription(description);
        }
    }

    /** Partial update payload — any subset of the create fields. */
    public record UpdatePropertyRequest(
            String title, String slug, String referenceCode,
            String propertyType, String listingType,
            BigDecimal price, String currency, Boolean priceNegotiable,
            String rentPeriod, String addressLine, String estateName,
            String lga, String state, String country, String landmark,
            BigDecimal latitude, BigDecimal longitude,
            Integer bedrooms, Integer bathrooms, Integer toilets,
            BigDecimal sizeSqm, BigDecimal plotSizeSqm, Integer yearBuilt,
            Integer parkingSpaces, List<String> features, List<String> images,
            String floorPlanUrl, String virtualTourUrl,
            Boolean hasCofO, Boolean hasDeedOfAssignment, Boolean hasSurveyPlan,
            Boolean hasGovernorConsent, Boolean hasGazette, String documentNotes,
            Boolean isPublic, Boolean featured, String description
    ) {
        void applyTo(Property p) {
            if (title != null) p.setTitle(title);
            if (slug != null) p.setSlug(slug);
            if (referenceCode != null) p.setReferenceCode(referenceCode);
            if (propertyType != null) p.setPropertyType(propertyType);
            if (listingType != null) p.setListingType(listingType);
            if (price != null) p.setPrice(price);
            if (currency != null) p.setCurrency(currency);
            if (priceNegotiable != null) p.setPriceNegotiable(priceNegotiable);
            if (rentPeriod != null) p.setRentPeriod(rentPeriod);
            if (addressLine != null) p.setAddressLine(addressLine);
            if (estateName != null) p.setEstateName(estateName);
            if (lga != null) p.setLga(lga);
            if (state != null) p.setState(state);
            if (country != null) p.setCountry(country);
            if (landmark != null) p.setLandmark(landmark);
            if (latitude != null) p.setLatitude(latitude);
            if (longitude != null) p.setLongitude(longitude);
            if (bedrooms != null) p.setBedrooms(bedrooms);
            if (bathrooms != null) p.setBathrooms(bathrooms);
            if (toilets != null) p.setToilets(toilets);
            if (sizeSqm != null) p.setSizeSqm(sizeSqm);
            if (plotSizeSqm != null) p.setPlotSizeSqm(plotSizeSqm);
            if (yearBuilt != null) p.setYearBuilt(yearBuilt);
            if (parkingSpaces != null) p.setParkingSpaces(parkingSpaces);
            if (features != null) p.setFeatures(features);
            if (images != null) p.setImages(images);
            if (floorPlanUrl != null) p.setFloorPlanUrl(floorPlanUrl);
            if (virtualTourUrl != null) p.setVirtualTourUrl(virtualTourUrl);
            if (hasCofO != null) p.setHasCofO(hasCofO);
            if (hasDeedOfAssignment != null) p.setHasDeedOfAssignment(hasDeedOfAssignment);
            if (hasSurveyPlan != null) p.setHasSurveyPlan(hasSurveyPlan);
            if (hasGovernorConsent != null) p.setHasGovernorConsent(hasGovernorConsent);
            if (hasGazette != null) p.setHasGazette(hasGazette);
            if (documentNotes != null) p.setDocumentNotes(documentNotes);
            if (isPublic != null) p.setPublic(isPublic);
            if (featured != null) p.setFeatured(featured);
            if (description != null) p.setDescription(description);
        }
    }

    public record StatusChange(@NotBlank String status) {
    }
}
