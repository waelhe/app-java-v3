package com.marketplace.shared.api;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator behind {@link Utf8ByteSize}: measures the value's UTF-8
 * encoding length against the constraint's byte budget. The JDK's own
 * {@link String#getBytes(String)} with {@code StandardCharsets.UTF_8}
 * never throws for a valid {@code String} — every Java string has a
 * well-defined UTF-8 form — so the measurement is a pure function of the
 * value (surrogate pairs included: one {@code 😀} is exactly 4 bytes,
 * which is the whole point of measuring bytes rather than characters).
 */
public class Utf8ByteSizeValidator implements ConstraintValidator<Utf8ByteSize, String> {

    private int maxBytes;

    @Override
    public void initialize(Utf8ByteSize constraint) {
        this.maxBytes = constraint.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes;
    }
}
