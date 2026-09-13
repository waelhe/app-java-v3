package com.marketplace.realestate;

import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The real-estate field set of one listing — L31's write contract (the PUT
 * body). Shape constraints only ({@code @NotNull} on the enum pair, bounds
 * on coordinates); the numeric/consistency gates live in the
 * {@code PropertyDetails} entity floor and the V48 CHECKs — the same
 * three-layer discipline as guest capacity (I6/V44).
 *
 * <p>The PUT is a full upsert (the plan: "create/update complete"):
 * omitted optional fields mean "undeclared", not "keep" — the property
 * block is replaced atomically on every accepted call.
 *
 * @param purpose      required — RENT or SALE
 * @param propertyType required — the physical kind
 * @param areaM2       positive when present
 * @param rooms        positive when present
 * @param bathrooms    positive when present
 * @param floorNumber  0 = ground, negatives = basement
 * @param totalFloors  must be >= floorNumber when both present
 * @param buildingYear 1800..2100 when present
 * @param furnished    tri-state flag
 * @param amenities    at most 20 names of at most 40 chars each (vocabulary documented on the entity — D-E2)
 * @param availableFrom optional availability date
 * @param locationId   optional geo node (validated against the tree)
 * @param latitude     optional display coordinate (bounded)
 * @param longitude    optional display coordinate (bounded)
 */
public record PropertyDetailsRequest(
        @NotNull PropertyPurpose purpose,
        @NotNull PropertyType propertyType,
        @Min(1) Integer areaM2,
        @Min(1) Integer rooms,
        @Min(1) Integer bathrooms,
        Integer floorNumber,
        @Min(0) Integer totalFloors,
        @Min(1800) @Max(2100) Integer buildingYear,
        Boolean furnished,
        List<String> amenities,
        LocalDate availableFrom,
        UUID locationId,
        @DecimalMin(value = "-90") @DecimalMax(value = "90") BigDecimal latitude,
        @DecimalMin(value = "-180") @DecimalMax(value = "180") BigDecimal longitude
) {
}
