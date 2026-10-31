package com.marketplace.identity.spi;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * typed address field set every address-lifecycle leg carries — one
 * record so the SPI seam, the services and the GraphQL boundary share
 * the exact same validated payload shape (the house record discipline).
 */
public record AddressFields(String firstName, String lastName, String companyName,
                            String streetAddress1, String streetAddress2,
                            String city, String cityArea, String postalCode,
                            String country, String countryArea, String phone) {

    /** The Saleor {@code AddressInput} validation: the geographic spine must exist. */
    public void validate() {
        if (country == null || !country.matches("^[A-Z]{2}$")) {
            throw new IllegalArgumentException(
                    "A two-letter ISO 3166-1 country code is required for an address");
        }
        if (isBlank(city) && isBlank(streetAddress1) && isBlank(postalCode)) {
            throw new IllegalArgumentException(
                    "An address requires at least a city, a street line or a postal code");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
