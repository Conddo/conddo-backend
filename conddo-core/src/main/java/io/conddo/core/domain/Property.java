package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A real-world piece of real estate — house, land, office — the tenant
 * is trying to sell, rent, or manage. Not a SKU. Tenant-scoped via RLS.
 *
 * <p>Nigerian real estate practice bakes in a few specifics that
 * generic "inventory" doesn't handle: separate document status fields
 * (C of O, Deed, Governor's Consent) because buyers ask "which papers
 * are ready?" before viewing; state + LGA + estate for address; a
 * {@link #listingType} that flips between sale, rent, and short-let.
 */
@Entity
@Table(name = "properties")
public class Property {

    // Enum-shaped strings kept as constants so the FE + BE + DB check
    // agree without a bidirectional Enum ↔ text mapper.
    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_AVAILABLE = "available";
    public static final String STATUS_RESERVED = "reserved";
    public static final String STATUS_UNDER_OFFER = "under_offer";
    public static final String STATUS_SOLD = "sold";
    public static final String STATUS_RENTED = "rented";
    public static final String STATUS_ARCHIVED = "archived";

    public static final String LISTING_SALE = "sale";
    public static final String LISTING_RENT = "rent";
    public static final String LISTING_SHORT_LET = "short-let";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    // ----- Identifying ------------------------------------------------------

    @Column(nullable = false)
    private String title;

    private String slug;

    @Column(name = "reference_code")
    private String referenceCode;

    // ----- Type + intent ----------------------------------------------------

    @Column(name = "property_type", nullable = false)
    private String propertyType;

    @Column(name = "listing_type", nullable = false)
    private String listingType;

    @Column(nullable = false)
    private String status = STATUS_DRAFT;

    // ----- Financial --------------------------------------------------------

    @Column(nullable = false)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(nullable = false)
    private String currency = "NGN";

    @Column(name = "price_negotiable", nullable = false)
    private boolean priceNegotiable = true;

    /** Only set for {@code listingType = rent}: annual / monthly / nightly. */
    @Column(name = "rent_period")
    private String rentPeriod;

    // ----- Location ---------------------------------------------------------

    @Column(name = "address_line")
    private String addressLine;

    @Column(name = "estate_name")
    private String estateName;

    private String lga;

    private String state;

    @Column(nullable = false)
    private String country = "Nigeria";

    private String landmark;

    private BigDecimal latitude;

    private BigDecimal longitude;

    // ----- Spec -------------------------------------------------------------

    private Integer bedrooms;
    private Integer bathrooms;
    private Integer toilets;

    @Column(name = "size_sqm")
    private BigDecimal sizeSqm;

    @Column(name = "plot_size_sqm")
    private BigDecimal plotSizeSqm;

    @Column(name = "year_built")
    private Integer yearBuilt;

    @Column(name = "parking_spaces")
    private Integer parkingSpaces;

    /** JSONB array of feature chips: borehole, 24hr power, gated, etc. */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> features = new ArrayList<>();

    /** JSONB array of Cloudinary URLs. First = primary hero image. */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> images = new ArrayList<>();

    @Column(name = "floor_plan_url")
    private String floorPlanUrl;

    @Column(name = "virtual_tour_url")
    private String virtualTourUrl;

    // ----- Documents (Nigerian real estate) ---------------------------------

    @Column(name = "has_c_of_o", nullable = false)
    private boolean hasCofO = false;

    @Column(name = "has_deed_of_assignment", nullable = false)
    private boolean hasDeedOfAssignment = false;

    @Column(name = "has_survey_plan", nullable = false)
    private boolean hasSurveyPlan = false;

    @Column(name = "has_governor_consent", nullable = false)
    private boolean hasGovernorConsent = false;

    @Column(name = "has_gazette", nullable = false)
    private boolean hasGazette = false;

    @Column(name = "document_notes")
    private String documentNotes;

    // ----- Ownership --------------------------------------------------------

    @Column(name = "listed_by_user_id")
    private UUID listedByUserId;

    @Column(name = "owner_id")
    private UUID ownerId;

    // ----- Visibility -------------------------------------------------------

    @Column(name = "public", nullable = false)
    private boolean isPublic = true;

    @Column(nullable = false)
    private boolean featured = false;

    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Property() {
    }

    public Property(UUID tenantId, String title, String propertyType, String listingType,
                    BigDecimal price) {
        this.tenantId = tenantId;
        this.title = title;
        this.propertyType = propertyType;
        this.listingType = listingType;
        this.price = price != null ? price : BigDecimal.ZERO;
    }

    // ----- Mutators ---------------------------------------------------------

    public void changeStatus(String newStatus) {
        this.status = newStatus;
    }

    public void updateFrom(Property patch) {
        // Merges non-null fields from a partial-update payload.
        if (patch.title != null) this.title = patch.title;
        if (patch.slug != null) this.slug = patch.slug;
        if (patch.referenceCode != null) this.referenceCode = patch.referenceCode;
        if (patch.propertyType != null) this.propertyType = patch.propertyType;
        if (patch.listingType != null) this.listingType = patch.listingType;
        if (patch.status != null) this.status = patch.status;
        if (patch.price != null) this.price = patch.price;
        if (patch.currency != null) this.currency = patch.currency;
        this.priceNegotiable = patch.priceNegotiable;
        if (patch.rentPeriod != null) this.rentPeriod = patch.rentPeriod;
        if (patch.addressLine != null) this.addressLine = patch.addressLine;
        if (patch.estateName != null) this.estateName = patch.estateName;
        if (patch.lga != null) this.lga = patch.lga;
        if (patch.state != null) this.state = patch.state;
        if (patch.country != null) this.country = patch.country;
        if (patch.landmark != null) this.landmark = patch.landmark;
        if (patch.latitude != null) this.latitude = patch.latitude;
        if (patch.longitude != null) this.longitude = patch.longitude;
        if (patch.bedrooms != null) this.bedrooms = patch.bedrooms;
        if (patch.bathrooms != null) this.bathrooms = patch.bathrooms;
        if (patch.toilets != null) this.toilets = patch.toilets;
        if (patch.sizeSqm != null) this.sizeSqm = patch.sizeSqm;
        if (patch.plotSizeSqm != null) this.plotSizeSqm = patch.plotSizeSqm;
        if (patch.yearBuilt != null) this.yearBuilt = patch.yearBuilt;
        if (patch.parkingSpaces != null) this.parkingSpaces = patch.parkingSpaces;
        if (patch.features != null) this.features = patch.features;
        if (patch.images != null) this.images = patch.images;
        if (patch.floorPlanUrl != null) this.floorPlanUrl = patch.floorPlanUrl;
        if (patch.virtualTourUrl != null) this.virtualTourUrl = patch.virtualTourUrl;
        this.hasCofO = patch.hasCofO;
        this.hasDeedOfAssignment = patch.hasDeedOfAssignment;
        this.hasSurveyPlan = patch.hasSurveyPlan;
        this.hasGovernorConsent = patch.hasGovernorConsent;
        this.hasGazette = patch.hasGazette;
        if (patch.documentNotes != null) this.documentNotes = patch.documentNotes;
        this.isPublic = patch.isPublic;
        this.featured = patch.featured;
        if (patch.description != null) this.description = patch.description;
    }

    // ----- Accessors --------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getReferenceCode() { return referenceCode; }
    public void setReferenceCode(String referenceCode) { this.referenceCode = referenceCode; }
    public String getPropertyType() { return propertyType; }
    public void setPropertyType(String propertyType) { this.propertyType = propertyType; }
    public String getListingType() { return listingType; }
    public void setListingType(String listingType) { this.listingType = listingType; }
    public String getStatus() { return status; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public boolean isPriceNegotiable() { return priceNegotiable; }
    public void setPriceNegotiable(boolean priceNegotiable) { this.priceNegotiable = priceNegotiable; }
    public String getRentPeriod() { return rentPeriod; }
    public void setRentPeriod(String rentPeriod) { this.rentPeriod = rentPeriod; }
    public String getAddressLine() { return addressLine; }
    public void setAddressLine(String addressLine) { this.addressLine = addressLine; }
    public String getEstateName() { return estateName; }
    public void setEstateName(String estateName) { this.estateName = estateName; }
    public String getLga() { return lga; }
    public void setLga(String lga) { this.lga = lga; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getLandmark() { return landmark; }
    public void setLandmark(String landmark) { this.landmark = landmark; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public Integer getBedrooms() { return bedrooms; }
    public void setBedrooms(Integer bedrooms) { this.bedrooms = bedrooms; }
    public Integer getBathrooms() { return bathrooms; }
    public void setBathrooms(Integer bathrooms) { this.bathrooms = bathrooms; }
    public Integer getToilets() { return toilets; }
    public void setToilets(Integer toilets) { this.toilets = toilets; }
    public BigDecimal getSizeSqm() { return sizeSqm; }
    public void setSizeSqm(BigDecimal sizeSqm) { this.sizeSqm = sizeSqm; }
    public BigDecimal getPlotSizeSqm() { return plotSizeSqm; }
    public void setPlotSizeSqm(BigDecimal plotSizeSqm) { this.plotSizeSqm = plotSizeSqm; }
    public Integer getYearBuilt() { return yearBuilt; }
    public void setYearBuilt(Integer yearBuilt) { this.yearBuilt = yearBuilt; }
    public Integer getParkingSpaces() { return parkingSpaces; }
    public void setParkingSpaces(Integer parkingSpaces) { this.parkingSpaces = parkingSpaces; }
    public List<String> getFeatures() { return features; }
    public void setFeatures(List<String> features) { this.features = features; }
    public List<String> getImages() { return images; }
    public void setImages(List<String> images) { this.images = images; }
    public String getFloorPlanUrl() { return floorPlanUrl; }
    public void setFloorPlanUrl(String floorPlanUrl) { this.floorPlanUrl = floorPlanUrl; }
    public String getVirtualTourUrl() { return virtualTourUrl; }
    public void setVirtualTourUrl(String virtualTourUrl) { this.virtualTourUrl = virtualTourUrl; }
    public boolean isHasCofO() { return hasCofO; }
    public void setHasCofO(boolean hasCofO) { this.hasCofO = hasCofO; }
    public boolean isHasDeedOfAssignment() { return hasDeedOfAssignment; }
    public void setHasDeedOfAssignment(boolean hasDeedOfAssignment) { this.hasDeedOfAssignment = hasDeedOfAssignment; }
    public boolean isHasSurveyPlan() { return hasSurveyPlan; }
    public void setHasSurveyPlan(boolean hasSurveyPlan) { this.hasSurveyPlan = hasSurveyPlan; }
    public boolean isHasGovernorConsent() { return hasGovernorConsent; }
    public void setHasGovernorConsent(boolean hasGovernorConsent) { this.hasGovernorConsent = hasGovernorConsent; }
    public boolean isHasGazette() { return hasGazette; }
    public void setHasGazette(boolean hasGazette) { this.hasGazette = hasGazette; }
    public String getDocumentNotes() { return documentNotes; }
    public void setDocumentNotes(String documentNotes) { this.documentNotes = documentNotes; }
    public UUID getListedByUserId() { return listedByUserId; }
    public void setListedByUserId(UUID listedByUserId) { this.listedByUserId = listedByUserId; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean aPublic) { isPublic = aPublic; }
    public boolean isFeatured() { return featured; }
    public void setFeatured(boolean featured) { this.featured = featured; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
