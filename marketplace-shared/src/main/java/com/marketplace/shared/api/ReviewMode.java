package com.marketplace.shared.api;

/**
 * W1 (yelp-level plan §4.1 — the owner's switch): the review-creation mode,
 * the closed vocabulary of the {@link SystemSettingKeys#REVIEWS_MODE}
 * setting.
 *
 * <p><b>Why the vocabulary lives in shared-api:</b> three modules read the
 * same setting off the same key — the review-creation gate
 * ({@code ReviewsService}, the plan's named reader), the provider public
 * page's dual-count display (§4.4), and the admin write surface's
 * validation. One enum makes the trio compile-checked against one
 * vocabulary instead of three string literals that can drift — the exact
 * silent-drift class {@link SystemSettingKeys} exists to close.
 *
 * <p><b>Fail loud, never fall back:</b> {@link #parse(String)} answers an
 * unknown value with {@link SystemSettingTypeException} (HTTP 500 — the W0
 * contract for "the control plane and the code disagree"), never a silent
 * default. The admin write surface validates against this same enum, so an
 * unparseable value cannot even be stored.
 */
public enum ReviewMode {

    /** The pre-W1 behaviour, byte for byte: the completed-booking gates alone. */
    VERIFIED_ONLY,

    /** Organic reviews only, no booking required — behind the §4.5 anti-abuse gates. */
    OPEN,

    /** Both paths open; the dual counters are displayed separately (§4.4). */
    HYBRID;

    /**
     * Parses the stored setting value strictly. {@code null} (no row AND no
     * caller default) and unknown values both raise
     * {@link SystemSettingTypeException} — the reader's own default is
     * applied by the caller through the port's {@code *OrDefault} form, so
     * the fallback is always visible at the call site.
     */
    public static ReviewMode parse(String raw) {
        if (raw == null) {
            throw new SystemSettingTypeException(
                    "system setting " + SystemSettingKeys.REVIEWS_MODE + " carries no value");
        }
        try {
            return valueOf(raw.trim());
        } catch (IllegalArgumentException unknown) {
            throw new SystemSettingTypeException("system setting "
                    + SystemSettingKeys.REVIEWS_MODE + " carries an unknown value: '" + raw
                    + "' — valid values: VERIFIED_ONLY, OPEN, HYBRID");
        }
    }
}
