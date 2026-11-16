package com.marketplace.shared.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a String is a live ISO 4217 alphabetic currency code
 * (three uppercase letters, e.g. {@code SAR}, {@code USD}, {@code EUR}).
 *
 * <p>The model itself is the JDK's own ISO 4217 implementation —
 * {@link java.util.Currency#getInstance(String)} — so validity follows the
 * runtime's currency table (Java 25), not a hand-maintained list. The
 * canonical form is normalised to uppercase before the lookup, so
 * {@code usd} and {@code USD} are both accepted and stored uppercase.</p>
 *
 * <p>By Bean Validation convention the constraint treats {@code null} and
 * blank strings as <em>valid</em>: it composes with optional fields whose
 * business default (e.g. {@code SAR}) is applied by the owning component.
 * Pair it with {@code @NotBlank} when the value is mandatory.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Repeatable(IsoCurrencyCode.List.class)
@Constraint(validatedBy = IsoCurrencyCodeValidator.class)
public @interface IsoCurrencyCode {

    String message() default "must be a valid ISO 4217 currency code (e.g. SAR, USD, EUR)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
    @interface List {
        IsoCurrencyCode[] value();
    }
}
