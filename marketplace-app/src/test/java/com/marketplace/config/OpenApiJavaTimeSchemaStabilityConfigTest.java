package com.marketplace.config;

import io.swagger.v3.core.util.PrimitiveType;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the java.time contract pin of
 * {@link OpenApiJavaTimeSchemaStabilityConfig}: the three types resolve to
 * the bare STRING primitive (overriding swagger-core 2.2.55's built-in
 * time formats that springdoc 3.1.1 #3351 would otherwise apply), and the
 * schemas the primitive creates carry no format in either OpenAPI mode —
 * the exact rendering the published baselines carry.
 */
class OpenApiJavaTimeSchemaStabilityConfigTest {

    @BeforeEach
    void applyTheContractPin() {
        new OpenApiJavaTimeSchemaStabilityConfig().restoreSpringDoc310JavaTimeRendering();
    }

    @Test
    void javaTimeTypesResolveToTheBareStringPrimitive() {
        assertThat(PrimitiveType.fromType(LocalTime.class)).isEqualTo(PrimitiveType.STRING);
        assertThat(PrimitiveType.fromType(OffsetTime.class)).isEqualTo(PrimitiveType.STRING);
        assertThat(PrimitiveType.fromType(Duration.class)).isEqualTo(PrimitiveType.STRING);
    }

    @Test
    void theResolvedPrimitiveRendersFormatFreeInBothOpenApiModes() {
        for (Class<?> type : new Class<?>[] { LocalTime.class, OffsetTime.class, Duration.class }) {
            PrimitiveType primitive = PrimitiveType.fromType(type);
            assertThat(primitive).isEqualTo(PrimitiveType.STRING);

            Schema<?> openapi30 = primitive.createProperty();
            assertThat(openapi30.getFormat())
                    .as("OpenAPI 3.0 rendering of %s must stay format-free", type.getSimpleName())
                    .isNull();

            Schema<?> openapi31 = primitive.createProperty31();
            assertThat(openapi31.getFormat())
                    .as("OpenAPI 3.1 rendering of %s must stay format-free", type.getSimpleName())
                    .isNull();
            assertThat(openapi31.getType() == "string"
                    || (openapi31.getTypes() != null && openapi31.getTypes().contains("string")))
                    .as("OpenAPI 3.1 rendering of %s must be a string", type.getSimpleName())
                    .isTrue();
        }
    }

    @Test
    void thePinIsIdempotent() {
        new OpenApiJavaTimeSchemaStabilityConfig().restoreSpringDoc310JavaTimeRendering();
        assertThat(PrimitiveType.fromType(LocalTime.class)).isEqualTo(PrimitiveType.STRING);
    }
}
