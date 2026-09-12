package com.marketplace.shared.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read port for a listing's real-estate details (realestate systems plan
 * L31 — implemented by the {@code marketplace-realestate} module).
 *
 * <p>Catalog embeds the property block into its listing detail responses
 * through this port (the plan's "application association in the
 * aggregation, not via JPA") — the exact {@code ProviderNameResolver}
 * pattern: the interface lives in shared-api, the owner module implements
 * it, the consumer injects it. No module boundary is crossed in code.
 */
public interface PropertyDetailsPort {

    /** The property block of one listing, when it exists. */
    Optional<PropertyView> findByListingId(UUID listingId);

    /**
     * Batch form for page aggregation — one query keyed by listing id
     * (no N+1: pages embed with a single round trip).
     */
    Map<UUID, PropertyView> findByListingIds(Set<UUID> listingIds);

    /**
     * Public read model of the real-estate details (the plan's L31 field
     * set). Coordinates are ordinary columns for client-side map display
     * (G-R5) — never for server-side radius search (the PostGIS gate).
     *
     * @param listingId    the catalog listing this block belongs to (1:1)
     * @param purpose      RENT or SALE
     * @param propertyType APARTMENT/VILLA/LAND/SHOP/OFFICE/GARAGE
     * @param areaM2       area in square meters (positive)
     * @param rooms        room count (positive)
     * @param bathrooms    bathroom count (positive)
     * @param floorNumber  floor (0 = ground, negatives = basements)
     * @param totalFloors  the building's floor count
     * @param buildingYear year of construction (null = undeclared)
     * @param furnished    furnished flag (null = undeclared)
     * @param amenities    documented vocabulary of feature names (D-E2)
     * @param availableFrom move-in / availability date (null = now)
     * @param locationId   the geo hierarchy node (L30)
     * @param latitude     optional display coordinate
     * @param longitude    optional display coordinate
     */
    record PropertyView(
            UUID listingId,
            PropertyPurpose purpose,
            PropertyType propertyType,
            Integer areaM2,
            Integer rooms,
            Integer bathrooms,
            Integer floorNumber,
            Integer totalFloors,
            Integer buildingYear,
            Boolean furnished,
            List<String> amenities,
            LocalDate availableFrom,
            UUID locationId,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }
}
