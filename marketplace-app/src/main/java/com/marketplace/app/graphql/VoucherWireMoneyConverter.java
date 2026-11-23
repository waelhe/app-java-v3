package com.marketplace.app.graphql;

import com.marketplace.pricing.spi.VoucherMutationError;
import com.marketplace.pricing.spi.VoucherMutationErrorCode;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * The voucher money boundary — the pinned Saleor 3.23.45
 * {@code PositiveDecimal} wire semantics onto the pricing owner's
 * internal exact-integer contract. The divergence ledger's "Decimal /
 * PositiveDecimal money" row governs: "Preserve Saleor scalar wire
 * representation and scale/range semantics" on the wire,
 * "currency-aware conversion to/from platform minor-unit" inside, "no
 * binary float for authoritative amounts" anywhere.
 *
 * <p><b>The wire contract this converter executes (the pinned
 * VoucherChannelListingAddInput semantics — Saleor's own carrier of
 * voucher money writes):</b></p>
 * <ul>
 *   <li>{@code discountValue} FIXED: MAJOR currency units — a dashboard
 *       sending {@code 100} means 100.00 (the IDP-01 finding's measured
 *       100x silent scale error closes here).</li>
 *   <li>{@code discountValue} PERCENTAGE: percentage points —
 *       {@code 7.5} means 7.5%, carried internally as 750 basis
 *       points (the pin's own Decimal wire carries the same 7.5).</li>
 *   <li>{@code minAmountSpent}: MAJOR currency units of the voucher's
 *       own denomination.</li>
 * </ul>
 *
 * <p>Every rejected conversion answers the classified payload error
 * ({@code DiscountError.code = INVALID} on the offending field) —
 * never a 500, never a silent clamp. Scale beyond the currency's own
 * minor-unit exponent (a 3-decimal amount on a 2-decimal currency) and
 * percentage precision beyond a basis point (0.01%) are both
 * classified rejections: the platform's internal integers cannot
 * represent them, and the honest answer is the error, not the
 * rounding.</p>
 *
 * <p><b>Range gates before arithmetic</b> (the catalog price filter's
 * own CWE-400 lesson): {@link BigDecimal#movePointRight} on a compact
 * scientific-notation value like {@code 1e+100000000} would materialize
 * a ~100-million-digit integer before {@code longValueExact()} narrows
 * it. The supported monetary range is gated FIRST through
 * {@link BigDecimal#compareTo} — which never materializes — so the
 * conversion is immune to the expansion.</p>
 */
final class VoucherWireMoneyConverter {

    /** The supported monetary ceiling in major units — the catalog price filter's own bounded range. */
    private static final BigDecimal MAX_MONEY_MAJOR = new BigDecimal("100000000");

    /** The percentage ceiling: 100 percentage points = 10000 basis points (the internal contract's own cap). */
    private static final BigDecimal MAX_PERCENTAGE = new BigDecimal("100");

    /** Percentage precision: whole basis points (0.01 percentage points). */
    private static final int MAX_PERCENTAGE_SCALE = 2;

    private VoucherWireMoneyConverter() {
    }

    /**
     * The converted money pair — the internal contract's own shapes:
     * {@code discountValue} in minor units (FIXED) or basis points
     * (PERCENTAGE), {@code minSpend} in minor units. Null components
     * pass through untouched (the service's own merge/required logic
     * owns them).
     */
    record WireMoney(Long discountValue, Long minSpend) {
    }

    /**
     * Converts the wire's PositiveDecimal money pair onto the internal
     * exact integers, appending classified errors for every rejected
     * value.
     *
     * @param discountValue      the wire decimal (major units for FIXED,
     *                           percentage points for PERCENTAGE); null passes through
     * @param discountValueType  the wire's type discriminator (PERCENTAGE
     *                           or FIXED); required whenever the value is present
     * @param minAmountSpent     the wire decimal minimum spend (major
     *                           units); null passes through
     * @param currency           the voucher's ISO-4217 denomination;
     *                           required whenever a currency-exponent conversion runs
     * @param errors             the accumulating payload-error list
     * @return the converted pair; components are null exactly when the
     *         input was null or a classified error was appended for them
     */
    static WireMoney convert(BigDecimal discountValue, String discountValueType,
                             BigDecimal minAmountSpent, String currency,
                             List<VoucherMutationError> errors) {
        boolean fixedValue = discountValue != null && "FIXED".equalsIgnoreCase(
                discountValueType == null ? "" : discountValueType.strip());
        boolean needsCurrency = fixedValue || minAmountSpent != null;
        Integer minorDigits = currencyMinorDigits(currency, needsCurrency, errors);
        Long convertedValue = convertDiscountValue(discountValue, discountValueType, minorDigits, errors);
        Long convertedMinSpend = convertMinAmountSpent(minAmountSpent, minorDigits, errors);
        return new WireMoney(convertedValue, convertedMinSpend);
    }

    /**
     * The currency's own minor-unit exponent through the JDK's
     * ISO-4217 registry ({@link Currency#getDefaultFractionDigits} —
     * 2 for USD/SAR, 0 for JPY, 3 for KWD). A missing currency where
     * the conversion needs one is the classified REQUIRED; a
     * well-formed but unregistered code is the classified INVALID —
     * the converter cannot know its exponent, and guessing one would
     * fake money semantics.
     */
    private static Integer currencyMinorDigits(String currency, boolean needed,
                                               List<VoucherMutationError> errors) {
        if (!needed) {
            return null;
        }
        if (currency == null || currency.isBlank()) {
            errors.add(new VoucherMutationError("currency", VoucherMutationErrorCode.REQUIRED,
                    "A voucher's money fields require the currency"));
            return null;
        }
        try {
            return Currency.getInstance(currency.strip().toUpperCase(Locale.ROOT)).getDefaultFractionDigits();
        } catch (IllegalArgumentException exception) {
            errors.add(new VoucherMutationError("currency", VoucherMutationErrorCode.INVALID,
                    "A voucher's currency is a registered ISO-4217 code"));
            return null;
        }
    }

    private static Long convertDiscountValue(BigDecimal discountValue, String discountValueType,
                                             Integer minorDigits, List<VoucherMutationError> errors) {
        if (discountValue == null) {
            return null;
        }
        if (discountValueType == null || discountValueType.isBlank()) {
            errors.add(new VoucherMutationError("discountValue", VoucherMutationErrorCode.INVALID,
                    "discountValue requires discountValueType — the value's unit semantics"));
            return null;
        }
        String type = discountValueType.strip().toUpperCase(Locale.ROOT);
        if ("PERCENTAGE".equals(type)) {
            return convertPercentage(discountValue, errors);
        }
        if ("FIXED".equals(type)) {
            if (minorDigits == null) {
                // The REQUIRED/INVALID currency error was already appended.
                errors.add(new VoucherMutationError("discountValue", VoucherMutationErrorCode.INVALID,
                        "A fixed voucher's value requires a convertible currency"));
                return null;
            }
            return convertMinorUnits(discountValue, "discountValue", minorDigits, errors);
        }
        // An unknown type string is the service's own classified INVALID —
        // the type error alone answers; no value conversion can run.
        return null;
    }

    private static Long convertPercentage(BigDecimal percentage, List<VoucherMutationError> errors) {
        if (percentage.stripTrailingZeros().scale() > MAX_PERCENTAGE_SCALE) {
            errors.add(new VoucherMutationError("discountValue", VoucherMutationErrorCode.INVALID,
                    "A percentage voucher's precision is one basis point (0.01%)"));
            return null;
        }
        if (percentage.compareTo(MAX_PERCENTAGE) > 0) {
            errors.add(new VoucherMutationError("discountValue", VoucherMutationErrorCode.INVALID,
                    "A percentage voucher carries at most 100.00%"));
            return null;
        }
        try {
            return percentage.movePointRight(MAX_PERCENTAGE_SCALE).longValueExact();
        } catch (ArithmeticException exception) {
            errors.add(new VoucherMutationError("discountValue", VoucherMutationErrorCode.INVALID,
                    "A percentage voucher carries at most 100.00%"));
            return null;
        }
    }

    private static Long convertMinorUnits(BigDecimal major, String field, int minorDigits,
                                          List<VoucherMutationError> errors) {
        if (major.stripTrailingZeros().scale() > minorDigits) {
            errors.add(new VoucherMutationError(field, VoucherMutationErrorCode.INVALID,
                    "The " + field + " precision exceeds the currency's own "
                            + minorDigits + " minor digits"));
            return null;
        }
        if (major.compareTo(MAX_MONEY_MAJOR) > 0) {
            errors.add(new VoucherMutationError(field, VoucherMutationErrorCode.INVALID,
                    "The supported monetary range is 0 .. 100000000 (major units)"));
            return null;
        }
        try {
            return major.movePointRight(minorDigits).longValueExact();
        } catch (ArithmeticException exception) {
            errors.add(new VoucherMutationError(field, VoucherMutationErrorCode.INVALID,
                    "The supported monetary range is 0 .. 100000000 (major units)"));
            return null;
        }
    }

    private static Long convertMinAmountSpent(BigDecimal minAmountSpent, Integer minorDigits,
                                              List<VoucherMutationError> errors) {
        if (minAmountSpent == null) {
            return null;
        }
        if (minorDigits == null) {
            // The REQUIRED/INVALID currency error was already appended.
            errors.add(new VoucherMutationError("minAmountSpent", VoucherMutationErrorCode.INVALID,
                    "minAmountSpent requires a convertible currency"));
            return null;
        }
        return convertMinorUnits(minAmountSpent, "minAmountSpent", minorDigits, errors);
    }
}
