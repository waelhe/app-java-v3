package com.marketplace.shared.api;

import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retro #435 review round's constraint: UTF-8 BYTES are the length domain
 * bcrypt and storage actually measure — the character-bound {@code @Size}
 * cannot see a 71-character password that is 73 bytes (69 ASCII + one emoji),
 * which used to reach the encoder and die as an internal exception instead of
 * the contract's own field error.
 */
@ExtendWith(MockitoExtension.class)
class Utf8ByteSizeValidatorTest {

    @Mock
    private ConstraintValidatorContext context;

    private final Utf8ByteSizeValidator validator = new Utf8ByteSizeValidator();

    private void init(int max) {
        Utf8ByteSize constraint = new Utf8ByteSize() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return Utf8ByteSize.class;
            }

            @Override
            public String message() {
                return "exceeds the maximum allowed UTF-8 byte length";
            }

            @Override
            public int max() {
                return max;
            }

            @Override
            public Class<?>[] groups() {
                return new Class<?>[0];
            }

            @Override
            public Class<? extends Payload>[] payload() {
                return (Class<? extends Payload>[]) new Class<?>[0];
            }
        };
        validator.initialize(constraint);
    }

    @Test
    void theEmojiBoundary_asciiPlusEmoji_isMeasuredInBytesNotCharacters() {
        init(72);
        // 69 ASCII characters + one 4-byte emoji = 71 Java characters, 73 bytes
        String seventyOneCharsSeventyThreeBytes = "a".repeat(69) + "😀";

        assertThat(validator.isValid(seventyOneCharsSeventyThreeBytes, context))
                .as("the character count passes @Size(72) but the encoding exceeds bcrypt's ceiling")
                .isFalse();
    }

    @Test
    void exactlyAtTheByteCeiling_passes() {
        init(72);
        assertThat(validator.isValid("a".repeat(72), context)).isTrue();
        // 68 ASCII + 2 two-byte chars (é) = 72 bytes exactly
        assertThat(validator.isValid("a".repeat(68) + "éé", context)).isTrue();
    }

    @Test
    void nullIsvalid_compositionWithMandatoryConstraints() {
        init(72);
        assertThat(validator.isValid(null, context)).isTrue();
    }

    @Test
    void multibyteOnlyStrings_measureTheirTrueEncodingLength() {
        init(8);
        // 2 × 😀 = 8 bytes exactly
        assertThat(validator.isValid("😀😀", context)).isTrue();
        // 3 × 😀 = 12 bytes
        assertThat(validator.isValid("😀😀😀", context)).isFalse();
    }
}
