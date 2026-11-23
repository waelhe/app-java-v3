package com.marketplace.identity.spi;

import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * bounded address projection behind the Saleor Users-domain address
 * lifecycle — typed, honest (only facts the {@code user_addresses} owner
 * actually owns) and carried through the {@code identity-spi} seam.
 */
public record AddressView(UUID id, UUID userId, String firstName, String lastName,
                          String companyName, String streetAddress1, String streetAddress2,
                          String city, String cityArea, String postalCode, String country,
                          String countryArea, String phone,
                          boolean defaultShipping, boolean defaultBilling) {
}
