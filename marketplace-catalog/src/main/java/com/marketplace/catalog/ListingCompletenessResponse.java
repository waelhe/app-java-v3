package com.marketplace.catalog;

import com.marketplace.shared.api.PropertyDetailsPort;

/**
 * L38 (realestate systems plan §5): the listing completeness score — a
 * pure, always-recomputed function over the listing's existing data. NO
 * storage (the plan's "حساب عابر بلا تخزين": recomputing on read is
 * cheaper than syncing a stored column), NO cache (the plan's acceptance
 * criterion 3: the score changes immediately when a photo lands — every
 * read is fresh by construction).
 *
 * <p><b>The documented equation (the plan's component list, equal
 * quarters):</b>
 * <pre>
 *   completeness% = 25 × (core fields)      + 25 × (photo)      +
 *                   25 × (property details) + 25 × (location)
 *
 *   core fields      — title present, description present, price declared
 *                      (title and price are schema-mandatory NOT NULL, but
 *                      the score MEASURES them instead of assuming them —
 *                      defence in depth; description is the live variable)
 *   photo            — at least one media asset in status UPLOADED
 *                      (MediaLookupPort's storage-verified-only count;
 *                      a PENDING_UPLOAD asset never counts)
 *   property details — the L31 realestate block exists (its purpose and
 *                      property type are schema-mandatory, so a present
 *                      block is never empty)
 *   location         — the L30 administrative node is attached
 *                      (property block's location_id; the optional
 *                      lat/lng display coordinates are NOT this criterion)
 * </pre>
 *
 * <p>A listing with everything answers {@code 100}; a listing with only
 * the core fields answers {@code 25}; the boundary values are asserted in
 * {@code ListingCompletenessTest}.
 *
 * <p>The four boolean components ride along so the provider sees exactly
 * WHICH quarter is missing (the score guides, the checklist tells where
 * to go) — the same read shape the plan's "معروضة للمزوّد في نقاط قراءته
 * فقط" describes.
 */
public record ListingCompletenessResponse(
        int percent,
        boolean coreFieldsPresent,
        boolean photosPresent,
        boolean propertyDetailsPresent,
        boolean locationPresent
) {

    /** Each of the plan's four component groups earns an equal quarter. */
    static final int PERCENT_PER_GROUP = 25;

    /**
     * The single source of the equation: measures the listing's own data,
     * the storage-verified photo count and the optional L31 property view.
     *
     * @param listing        the catalog listing (title/description/price)
     * @param uploadedPhotos the UPLOADED asset count for the listing
     * @param property       the L31 property block, {@code null} when the
     *                       listing has none
     */
    public static ListingCompletenessResponse of(ProviderListing listing,
                                                 long uploadedPhotos,
                                                 PropertyDetailsPort.PropertyView property) {
        boolean core = listing.getTitle() != null && !listing.getTitle().isBlank()
                && listing.getDescription() != null && !listing.getDescription().isBlank()
                && listing.getPriceCents() != null;
        boolean photos = uploadedPhotos >= 1;
        boolean details = property != null;
        boolean location = property != null && property.locationId() != null;
        int percent = (core ? PERCENT_PER_GROUP : 0)
                + (photos ? PERCENT_PER_GROUP : 0)
                + (details ? PERCENT_PER_GROUP : 0)
                + (location ? PERCENT_PER_GROUP : 0);
        return new ListingCompletenessResponse(percent, core, photos, details, location);
    }
}
