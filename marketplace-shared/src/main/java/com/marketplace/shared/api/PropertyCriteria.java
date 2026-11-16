package com.marketplace.shared.api;

import java.util.Set;
import java.util.UUID;

/**
 * The resolved real-estate filter contract carried into the
 * {@link RealestatePropertyFilterPort} (realestate systems plan L32 — the
 * CodeRabbit-adopted set-restriction integration).
 *
 * <p>Two kinds of components ride here, both already gated upstream by the
 * {@link SearchCriteria} type gates: the raw facet criteria
 * (purpose/type/minRooms/minBathrooms/minAreaM2 — present or null) and the
 * SERVER-DERIVED restriction {@code locationIds} — the geo-resolved
 * qualified location set (the searched node plus its descendants, from
 * {@code GeoLookupPort.findSelfAndDescendants}); {@code null} = no
 * location criterion. The port never re-resolves the tree (the plan:
 * "the search does not fiddle with tree depth" — it passes the resolved
 * set through).
 *
 * @param purpose      RENT/SALE or null (no criterion)
 * @param propertyType the physical kind or null
 * @param minRooms     positive or null
 * @param minBathrooms positive or null
 * @param minAreaM2    positive or null
 * @param locationIds  the geo-resolved qualified set — null = criterion
 *                     absent; non-null = non-empty (an existing node's
 *                     self+descendants)
 */
public record PropertyCriteria(
        PropertyPurpose purpose,
        PropertyType propertyType,
        Integer minRooms,
        Integer minBathrooms,
        Integer minAreaM2,
        Set<UUID> locationIds
) {

    /** Whether any facet is present (drives the empty-answer short-circuit). */
    public boolean hasAnyCriterion() {
        return purpose != null || propertyType != null
                || minRooms != null || minBathrooms != null || minAreaM2 != null
                || locationIds != null;
    }
}
