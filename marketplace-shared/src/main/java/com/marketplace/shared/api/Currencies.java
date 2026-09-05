package com.marketplace.shared.api;

import java.util.Currency;

/**
 * ISO 4217 minor-unit currency helpers shared by the money-carrying
 * components (listing, booking, payment intent, exchange). The ISO 4217
 * model itself is the JDK's {@link Currency} — this helper only normalises
 * and defends the string form that crosses REST/JPA boundaries.
 */
public final class Currencies {

    /** House default: the marketplace's base listing currency. */
    public static final String DEFAULT_CODE = "SAR";

    private Currencies() {
    }

    /**
     * Normalises a currency code: trims, uppercases. Blank stays blank/null
     * (the caller decides the fallback); a non-ISO code throws
     * {@link IllegalArgumentException} — validity authority is
     * {@link Currency#getInstance(String)}.
     */
    public static String normalize(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        String candidate = code.strip().toUpperCase(java.util.Locale.ROOT);
        Currency.getInstance(candidate); // ISO 4217 authority — throws on unknown code
        return candidate;
    }

    /**
     * Normalises with a fallback: blank/null resolves to
     * {@code fallback}, any other value must be a valid ISO 4217 code.
     */
    public static String normalizeOrDefault(String code, String fallback) {
        String normalized = normalize(code);
        return (normalized == null || normalized.isBlank()) ? fallback : normalized;
    }
}
