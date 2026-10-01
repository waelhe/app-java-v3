package com.marketplace.shared.api;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a String's UTF-8 encoding fits within the given byte
 * budget — the length domain storage formats and hash algorithms actually
 * measure (the retro #435 review round: a password of 69 ASCII characters
 * plus one {@code 😀} is 71 Java characters but 73 UTF-8 bytes — it passed
 * {@code @Size(max = 72)} and then the bcrypt encoder refused it at the
 * byte boundary with an internal exception instead of the contract's own
 * field error).
 *
 * <p>The canonical use is bcrypt's documented 72-byte ceiling (Spring
 * Security's {@code BCryptPasswordEncoder} refuses longer inputs instead
 * of silently truncating): pair {@code @Utf8ByteSize(max = 72)} with the
 * character-bound {@code @Size} — the pair states both halves of the
 * contract (the character bound for humans, the byte bound for the
 * algorithm).</p>
 *
 * <p>By Bean Validation convention (the {@link IsoCurrencyCode} house
 * pattern this follows), {@code null} is <em>valid</em>: the constraint
 * composes with {@code @NotBlank}/{@code @Size(min = ...)} when the value
 * is mandatory.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Constraint(validatedBy = Utf8ByteSizeValidator.class)
public @interface Utf8ByteSize {

    /**
     * The message carries the measured fact: the bound is on UTF-8 bytes,
     * not characters — so the client understands why a 71-character
     * password can still be one byte too long.
     */
    String message() default "exceeds the maximum allowed UTF-8 byte length";

    /** The inclusive maximum number of UTF-8 bytes. */
    int max();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
