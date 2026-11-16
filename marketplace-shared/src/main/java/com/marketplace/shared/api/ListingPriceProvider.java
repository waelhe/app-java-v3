package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Port that allows other modules to look up listing pricing information
 * without depending on the catalog module directly.
 *
 * <p><b>Design decision — synchronous interface vs. event:</b>
 * Spring Modulith's primary recommendation for cross-module interaction is
 * event publication/consumption. However, this port uses a synchronous
 * interface call because the caller ({@code BookingService.create()}) needs
 * the listing's price and provider <em>before</em> persisting the booking.
 * An asynchronous event cannot satisfy this requirement — the booking must
 * be created with the correct price atomically within the same transaction.
 * This follows the Modulith guidance that synchronous queries are acceptable
 * when the result is needed <em>within</em> the current operation.</p>
 *
 * @see BookingParticipantProvider
 */
public interface ListingPriceProvider {

    /**
     * Returns the provider ID, price and ISO 4217 currency for the given
     * listing.
     *
     * @throws ResourceNotFoundException if the listing does not exist
     */
    ListingInfo getListingInfo(UUID listingId);

    /**
     * Carrier of server-derived listing data.
     * Immutable value object — no behaviour.
     *
     * @param currency ISO 4217 alphabetic code of the listing price —
     *                 the money the booking will carry
     */
    record ListingInfo(UUID providerId, Long priceCents, String currency) {

        /**
         * Pre-currency convenience constructor (roadmap B4 kept it
         * source-compatible): defaults the currency to the house code SAR
         * exactly like the pre-existing callers assumed.
         */
        public ListingInfo(UUID providerId, Long priceCents) {
            this(providerId, priceCents, com.marketplace.shared.api.Currencies.DEFAULT_CODE);
        }

        public ListingInfo {
            if (currency == null || currency.isBlank()) {
                throw new IllegalStateException("Listing has invalid currency: " + currency);
            }
            currency = com.marketplace.shared.api.Currencies.normalize(currency);
        }
    }
}
