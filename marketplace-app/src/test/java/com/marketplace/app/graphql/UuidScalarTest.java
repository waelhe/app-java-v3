package com.marketplace.app.graphql;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.GraphQLContext;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.execution.CoercedVariables;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UuidScalarTest {

    private static final GraphQLContext CONTEXT = GraphQLContext.newContext().build();
    private static final Locale LOCALE = Locale.ROOT;
    private static final UUID EXPECTED = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    private final UuidScalar scalar = new UuidScalar();

    @Test
    void registersUnderTheCentralUuidScalarName() {
        DgsScalar registration = UuidScalar.class.getAnnotation(DgsScalar.class);

        assertThat(registration).isNotNull();
        assertThat(registration.name()).isEqualTo("UUID");
    }

    @Test
    void centralRuntimeSchemaDeclaresUuidExactlyOnce() throws Exception {
        try (InputStream schemaStream = getClass().getClassLoader()
                .getResourceAsStream("graphql/schema.graphqls")) {
            assertThat(schemaStream).isNotNull();
            String schema = new String(schemaStream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(schema.lines()
                    .filter(line -> line.trim().equals("scalar UUID"))
                    .count()).isEqualTo(1);
        }
    }

    @Test
    void parsesCanonicalUuidVariable() {
        assertThat(scalar.parseValue(EXPECTED.toString(), CONTEXT, LOCALE)).isEqualTo(EXPECTED);
    }

    @Test
    void acceptsUppercaseHexButNormalizesOutput() {
        String uppercase = EXPECTED.toString().toUpperCase(Locale.ROOT);
        assertThat(scalar.parseValue(uppercase, CONTEXT, LOCALE)).isEqualTo(EXPECTED);
        assertThat(scalar.serialize(uppercase, CONTEXT, LOCALE)).isEqualTo(EXPECTED.toString());
    }

    @Test
    void parsesStringLiteral() {
        assertThat(scalar.parseLiteral(StringValue.newStringValue().value(EXPECTED.toString()).build(),
                CoercedVariables.emptyVariables(), CONTEXT, LOCALE)).isEqualTo(EXPECTED);
    }

    @Test
    void serializesUuidToCanonicalText() {
        assertThat(scalar.serialize(EXPECTED, CONTEXT, LOCALE)).isEqualTo(EXPECTED.toString());
    }

    @Test
    void rejectsMalformedAndNonCanonicalValues() {
        assertThatThrownBy(() -> scalar.parseValue("not-a-uuid", CONTEXT, LOCALE))
                .isInstanceOf(CoercingParseValueException.class);
        assertThatThrownBy(() -> scalar.parseValue("1-2-3-4-5", CONTEXT, LOCALE))
                .isInstanceOf(CoercingParseValueException.class);
    }

    @Test
    void rejectsNonStringVariablesAndLiterals() {
        assertThatThrownBy(() -> scalar.parseValue(123, CONTEXT, LOCALE))
                .isInstanceOf(CoercingParseValueException.class);
        assertThatThrownBy(() -> scalar.parseLiteral(
                IntValue.newIntValue().value(BigInteger.ONE).build(),
                CoercedVariables.emptyVariables(), CONTEXT, LOCALE))
                .isInstanceOf(CoercingParseLiteralException.class);
    }

    @Test
    void rejectsInvalidSerializedValue() {
        assertThatThrownBy(() -> scalar.serialize("not-a-uuid", CONTEXT, LOCALE))
                .isInstanceOf(CoercingSerializeException.class);
        assertThatThrownBy(() -> scalar.serialize(123, CONTEXT, LOCALE))
                .isInstanceOf(CoercingSerializeException.class);
    }

    /**
     * The Coercing contract's own closure (the 2026-11-03 #806 review,
     * Minor · Stability & Availability): every external value
     * {@code parseValue} accepts, {@code valueToLiteral} must accept —
     * and the literal it answers must be valid for {@code parseLiteral}.
     * The inherited default threw {@code UnsupportedOperationException},
     * so a valid {@code token} variable could crash value-to-literal
     * conversion (normalized-operation rewriting).
     */
    @Test
    void valueToLiteralAcceptsEveryParseValueInputAndRoundTrips() {
        // A UUID value answers its canonical text literal...
        graphql.language.Value<?> fromUuid = scalar.valueToLiteral(EXPECTED, CONTEXT, LOCALE);
        assertThat(fromUuid).isInstanceOf(StringValue.class);
        assertThat(((StringValue) fromUuid).getValue()).isEqualTo(EXPECTED.toString());
        // ...a canonical String variable (the external form) likewise...
        graphql.language.Value<?> fromString =
                scalar.valueToLiteral(EXPECTED.toString(), CONTEXT, LOCALE);
        assertThat(((StringValue) fromString).getValue()).isEqualTo(EXPECTED.toString());
        // ...an UPPERCASE canonical form normalizes to the lower-case
        // canonical literal (the same normalization serialize applies)...
        graphql.language.Value<?> fromUppercase =
                scalar.valueToLiteral(EXPECTED.toString().toUpperCase(java.util.Locale.ROOT), CONTEXT, LOCALE);
        assertThat(((StringValue) fromUppercase).getValue()).isEqualTo(EXPECTED.toString());
        // ...and the answered literal is valid for parseLiteral (the
        // contract's own round-trip rule).
        assertThat(scalar.parseLiteral(fromUuid, CoercedVariables.emptyVariables(), CONTEXT, LOCALE))
                .isEqualTo(EXPECTED);
    }

    @Test
    void valueToLiteralRejectsWhatParseValueRejects() {
        assertThatThrownBy(() -> scalar.valueToLiteral("not-a-uuid", CONTEXT, LOCALE))
                .isInstanceOf(CoercingParseValueException.class);
        assertThatThrownBy(() -> scalar.valueToLiteral(123, CONTEXT, LOCALE))
                .isInstanceOf(CoercingParseValueException.class);
    }
}
