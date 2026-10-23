package com.marketplace.app.graphql;

import graphql.GraphQLContext;
import graphql.language.StringValue;
import graphql.schema.CoercedVariables;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import org.junit.jupiter.api.Test;

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
        assertThatThrownBy(() -> scalar.parseLiteral(CoercedVariables.emptyVariables() == null
                        ? null : graphql.language.IntValue.newIntValue().value(java.math.BigInteger.ONE).build(),
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
}
