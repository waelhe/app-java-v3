package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsoCurrencyCodeValidatorTest {

    private final IsoCurrencyCodeValidator validator = new IsoCurrencyCodeValidator();

    @ParameterizedTest
    @ValueSource(strings = {"SAR", "USD", "EUR", "JPY", "GBP", "AED", "KWD"})
    void acceptsLiveIso4217Codes(String code) {
        assertThat(validator.isValid(code, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sar", "Usd", " eur "})
    void acceptsCaseInsensitiveAndSurroundingSpace(String code) {
        assertThat(validator.isValid(code, null)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void blankPassesThrough_optionalFieldComposition(String code) {
        // The constraint composes with optional fields whose business default
        // is applied elsewhere (e.g. SAR for listings).
        assertThat(validator.isValid(code, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"XYZ", "USDD", "US", "12", "U1D", "SA R"})
    void rejectsNonIso4217Codes(String code) {
        assertThat(validator.isValid(code, null)).isFalse();
    }

    @Test
    void currencies_normalizeUppercasesValidCode() {
        assertThat(Currencies.normalize("usd")).isEqualTo("USD");
        assertThat(Currencies.normalize(" sar ")).isEqualTo("SAR");
    }

    @Test
    void currencies_normalizeBlankStaysBlank_fallbackIsCallersDecision() {
        assertThat(Currencies.normalize(null)).isNull();
        assertThat(Currencies.normalize("  ")).isEqualTo("  ");
    }

    @Test
    void currencies_normalizeThrowsForUnknownCode_isoAuthorityIsJdk() {
        assertThatThrownBy(() -> Currencies.normalize("XYZ"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currencies_normalizeOrDefault_appliesFallbackOnlyForBlank() {
        assertThat(Currencies.normalizeOrDefault(null, "SAR")).isEqualTo("SAR");
        assertThat(Currencies.normalizeOrDefault("  ", "SAR")).isEqualTo("SAR");
        assertThat(Currencies.normalizeOrDefault("eur", "SAR")).isEqualTo("EUR");
    }

    @Test
    void listingInfo_legacyConstructor_defaultsToHouseCurrency() {
        var info = new ListingPriceProvider.ListingInfo(java.util.UUID.randomUUID(), 5000L);
        assertThat(info.currency()).isEqualTo("SAR");
    }

    @Test
    void listingInfo_canonicalConstructor_normalizesIsoCode() {
        var info = new ListingPriceProvider.ListingInfo(java.util.UUID.randomUUID(), 5000L, "usd");
        assertThat(info.currency()).isEqualTo("USD");
    }

    @Test
    void bookingInfo_normalizesIsoCode_atTheCrossingPoint() {
        var info = new BookingInfo(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "CONFIRMED", 5000L, "usd", java.time.Instant.now(), java.time.Instant.now());
        assertThat(info.currency()).isEqualTo("USD");
    }
}
