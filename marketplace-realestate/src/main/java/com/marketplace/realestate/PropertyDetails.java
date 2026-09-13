package com.marketplace.realestate;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * The real-estate field set of exactly one catalog listing (realestate
 * systems plan L31 — the vertical-module pattern of {@code MediaAsset}).
 *
 * <p>Cross-module references follow the house convention: {@code listingId}
 * and {@code providerId} are plain UUID columns with NO JPA relation across
 * module boundaries (same as {@code Review.bookingId} / {@code
 * MediaAsset.listingId}); the catalog listing is resolved through the
 * shared ports. {@code listingId} is UNIQUE — one property block per
 * listing, enforced by the entity's upsert flow and backed by the V48
 * unique constraint.
 *
 * <p>Deletion follows the listing: the block is soft-deleted with it (the
 * public read gates on the listing being ACTIVE, so an archived or
 * soft-deleted listing hides its details without any physical delete).
 *
 * <p>Validation is the entity floor of the three-layer gate (I6/V44
 * discipline): every numeric/consistency rule that the request-level Bean
 * Validation expresses is re-asserted here so no writer can bypass it, and
 * the V48 CHECKs back the floor at the database.
 */
@Entity
@Table(name = "property_details")
@Audited
public class PropertyDetails extends BaseEntity {

    /**
     * The amenity vocabulary recommended for the {@code amenities} list
     * (plan debt D-E2: documented in javadoc first, no JSON Schema until a
     * consumer needs one). Free strings are ACCEPTED (the vocabulary is a
     * recommendation, not a closed gate) — the closure point is the first
     * consuming surface that needs typed amenities.
     */
    public static final List<String> DOCUMENTED_AMENITIES = List.of(
            "elevator", "parking", "garage", "balcony", "garden", "pool",
            "security", "furnished_kitchen", "ac", "heating", "internet",
            "generator", "water_tank", "private_entrance", "storage");

    private static final int MAX_AMENITIES = 20;
    private static final int MAX_AMENITY_LENGTH = 40;

    @Id
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 10)
    private PropertyPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", nullable = false, length = 20)
    private PropertyType propertyType;

    @Column(name = "area_m2")
    private Integer areaM2;

    @Column(name = "rooms")
    private Integer rooms;

    @Column(name = "bathrooms")
    private Integer bathrooms;

    @Column(name = "floor_number")
    private Integer floorNumber;

    @Column(name = "total_floors")
    private Integer totalFloors;

    @Column(name = "building_year")
    private Integer buildingYear;

    @Column(name = "furnished")
    private Boolean furnished;

    /**
     * The documented amenity names — JSONB per the plan (D-E2: no strict
     * JSON Schema yet; the recommended vocabulary is the class javadoc
     * above). Official Hibernate JSON mapping: {@code @JdbcTypeCode(SqlTypes.JSON)}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "amenities", columnDefinition = "jsonb")
    private List<String> amenities;

    @Column(name = "available_from")
    private LocalDate availableFrom;

    /** The geo hierarchy node (L30) — resolved/validated via GeoLookupPort. */
    @Column(name = "location_id")
    private UUID locationId;

    /**
     * The coordinate (G-R5: client-side maps). P1 (postgis plan §D-P1):
     * also the radius-search source — the V50 expressive GiST index
     * evaluates {@code ST_SetSRID(ST_MakePoint(longitude, latitude),
     * 4326)::geography} over these numeric columns, so the stored value
     * stays the single representation (no geography column, no mirror);
     * the value is written once and read by both consumers.
     */
    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    /**
     * The coordinate (G-R5: client-side maps) — P1: the radius-search
     * source through the same V50 expressive index (see the latitude
     * field's javadoc).
     */
    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    protected PropertyDetails() {
    }

    private PropertyDetails(UUID id, UUID listingId, UUID providerId) {
        this.id = id;
        this.listingId = listingId;
        this.providerId = providerId;
    }

    /**
     * Creates the property block of a listing (the upsert's insert arm).
     * The full field set is validated by {@link #apply(PropertyDetailsRequest)}.
     */
    public static PropertyDetails create(UUID listingId, UUID providerId,
                                         PropertyDetailsRequest request) {
        PropertyDetails details = new PropertyDetails(UUID.randomUUID(), listingId, providerId);
        details.apply(request);
        return details;
    }

    /**
     * Replaces the whole field set (PUT semantics — the plan's "complete
     * update"). Every gate is the entity floor: positivity, floor/total
     * consistency, bounded year, amenity shape, bounded coordinates.
     */
    public void apply(PropertyDetailsRequest request) {
        if (request == null || request.purpose() == null || request.propertyType() == null) {
            throw new BadRequestException("purpose and propertyType are required");
        }
        this.purpose = request.purpose();
        this.propertyType = request.propertyType();
        this.areaM2 = requirePositive(request.areaM2(), "areaM2");
        this.rooms = requirePositive(request.rooms(), "rooms");
        this.bathrooms = requirePositive(request.bathrooms(), "bathrooms");
        this.floorNumber = request.floorNumber();
        this.totalFloors = requireNonNegative(request.totalFloors(), "totalFloors");
        if (this.floorNumber != null && this.totalFloors != null
                && this.floorNumber > this.totalFloors) {
            throw new BadRequestException(
                    "floorNumber must not exceed totalFloors");
        }
        this.buildingYear = request.buildingYear();
        if (this.buildingYear != null && (this.buildingYear < 1800 || this.buildingYear > 2100)) {
            throw new BadRequestException("buildingYear must be between 1800 and 2100");
        }
        this.furnished = request.furnished();
        this.amenities = normalizeAmenities(request.amenities());
        this.availableFrom = request.availableFrom();
        this.locationId = request.locationId();
        this.latitude = request.latitude();
        this.longitude = request.longitude();
        if (this.latitude != null
                && (this.latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || this.latitude.compareTo(BigDecimal.valueOf(90)) > 0)) {
            throw new BadRequestException("latitude must be within [-90, 90]");
        }
        if (this.longitude != null
                && (this.longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || this.longitude.compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw new BadRequestException("longitude must be within [-180, 180]");
        }
    }

    @Override
    public UUID getId() { return id; }
    public UUID getListingId() { return listingId; }
    public UUID getProviderId() { return providerId; }
    public PropertyPurpose getPurpose() { return purpose; }
    public PropertyType getPropertyType() { return propertyType; }
    public Integer getAreaM2() { return areaM2; }
    public Integer getRooms() { return rooms; }
    public Integer getBathrooms() { return bathrooms; }
    public Integer getFloorNumber() { return floorNumber; }
    public Integer getTotalFloors() { return totalFloors; }
    public Integer getBuildingYear() { return buildingYear; }
    public Boolean getFurnished() { return furnished; }
    public List<String> getAmenities() { return amenities; }
    public LocalDate getAvailableFrom() { return availableFrom; }
    public UUID getLocationId() { return locationId; }
    public BigDecimal getLatitude() { return latitude; }
    public BigDecimal getLongitude() { return longitude; }

    private static Integer requirePositive(Integer value, String field) {
        if (value != null && value <= 0) {
            throw new BadRequestException(field + " must be positive when provided");
        }
        return value;
    }

    private static Integer requireNonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new BadRequestException(field + " must not be negative");
        }
        return value;
    }

    /**
     * Amenity shape: bounded in count and length, blanks and duplicates
     * rejected explicitly (the type-gate philosophy — a duplicate is a
     * client mistake, never silently deduped). Order is preserved as
     * given (the caller's order IS the display order). The recommended
     * vocabulary is {@link #DOCUMENTED_AMENITIES} (D-E2).
     */
    private static List<String> normalizeAmenities(List<String> amenities) {
        if (amenities == null || amenities.isEmpty()) {
            return List.of();
        }
        if (amenities.size() > MAX_AMENITIES) {
            throw new BadRequestException("at most " + MAX_AMENITIES + " amenities");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> normalized = new ArrayList<>();
        for (String amenity : amenities) {
            String trimmed = amenity == null ? "" : amenity.trim();
            if (trimmed.isEmpty()) {
                throw new BadRequestException("amenity names must not be blank");
            }
            if (trimmed.length() > MAX_AMENITY_LENGTH) {
                throw new BadRequestException(
                        "amenity names must be at most " + MAX_AMENITY_LENGTH + " characters");
            }
            if (!seen.add(trimmed)) {
                throw new BadRequestException("duplicate amenity: " + trimmed);
            }
            normalized.add(trimmed);
        }
        return normalized;
    }
}
