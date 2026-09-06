package com.marketplace.shared.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Currency;

/**
 * Validator behind {@link IsoCurrencyCode}: delegates validity to the JDK's
 * ISO 4217 implementation ({@link Currency#getInstance(String)}). Blank
 * values pass through (composition with optional fields — see the annotation
 * Javadoc).
 */
public class IsoCurrencyCodeValidator implements ConstraintValidator<IsoCurrencyCode, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String candidate = value.strip().toUpperCase(java.util.Locale.ROOT);
        if (candidate.length() != 3) {
            return false;
        }
        try {
            // ISO 4217 authority: the JDK's own currency table. Throws
            // IllegalArgumentException for unknown codes.
            Currency.getInstance(candidate);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
