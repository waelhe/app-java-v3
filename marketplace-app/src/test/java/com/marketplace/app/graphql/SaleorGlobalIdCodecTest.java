package com.marketplace.app.graphql;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaleorGlobalIdCodecTest {

    private static final String TYPE = "Product";
    private static final UUID NATIVE_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String GLOBAL_ID =
            "UHJvZHVjdDoxMjNlNDU2Ny1lODliLTEyZDMtYTQ1Ni00MjY2MTQxNzQwMDA=";

    private final SaleorGlobalIdCodec codec = new SaleorGlobalIdCodec();

    @Test
    void encodesTypedUuidUsingCanonicalStandardBase64() {
        assertThat(codec.encode(TYPE, NATIVE_ID)).isEqualTo(GLOBAL_ID);
    }

    @Test
    void decodesTypedUuidForTheExpectedGraphQlType() {
        assertThat(codec.decode(GLOBAL_ID, TYPE)).isEqualTo(NATIVE_ID);
    }

    @Test
    void rejectsGlobalIdWhoseTypeDoesNotMatchTheExpectedType() {
        assertInvalid(() -> codec.decode(GLOBAL_ID, "User"));
    }

    @Test
    void rejectsMalformedBase64AndNonCanonicalBase64() {
        assertInvalid(() -> codec.decode("not-base64!", TYPE));
        assertInvalid(() -> codec.decode(GLOBAL_ID.replace("=", ""), TYPE));
    }

    @Test
    void rejectsInvalidUtf8Payload() {
        assertInvalid(() -> codec.decode("/w==", TYPE));
    }

    @Test
    void rejectsMissingOrRepeatedTypeSeparator() {
        assertInvalid(() -> codec.decode(encodePayload("Product"), TYPE));
        assertInvalid(() -> codec.decode(encodePayload("Product:" + NATIVE_ID + ":extra"), TYPE));
    }

    @Test
    void rejectsMalformedOrNonCanonicalUuid() {
        assertInvalid(() -> codec.decode(encodePayload("Product:not-a-uuid"), TYPE));
        assertInvalid(() -> codec.decode(encodePayload("Product:123e4567-e89b-12d3-a456-42661417400A"), TYPE));
        assertInvalid(() -> codec.decode(NATIVE_ID.toString(), TYPE));
    }

    @Test
    void rejectsInvalidGraphQlTypeNames() {
        assertInvalid(() -> codec.encode("Product:User", NATIVE_ID));
        assertInvalid(() -> codec.encode("1Product", NATIVE_ID));
        assertInvalid(() -> codec.decode(GLOBAL_ID, "Product:User"));
        assertInvalid(() -> codec.decode(GLOBAL_ID, "A".repeat(129)));
    }

    @Test
    void rejectsNullBlankAndOversizedInputs() {
        assertInvalid(() -> codec.encode(TYPE, null));
        assertInvalid(() -> codec.encode(null, NATIVE_ID));
        assertInvalid(() -> codec.decode(null, TYPE));
        assertInvalid(() -> codec.decode("   ", TYPE));
        assertInvalid(() -> codec.decode("A".repeat(257), TYPE));
    }

    private static String encodePayload(String payload) {
        return Base64.getEncoder().encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation) {
        assertThatThrownBy(invocation)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid global ID");
    }
}
