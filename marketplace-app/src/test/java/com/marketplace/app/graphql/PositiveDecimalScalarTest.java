package com.marketplace.app.graphql;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.GraphQLContext;
import graphql.language.FloatValue;
import graphql.language.Value;
import graphql.schema.CoercingParseValueException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 2026-11-08 #806 round-9 review (two Minors · Functional
 * Correctness): the Coercing contract's own closure — "every valid
 * external input value for parseValue is also valid for valueToLiteral,
 * and the literals returned are valid for parseLiteral". The previous
 * {@code valueToLiteral} rejected the String values {@code parseValue}
 * accepts ("1.25") and funneled the Number branch through a double,
 * silently losing the last digit of {@code 9007199254740993L}. The full
 * delegation to the shared {@code toDecimal} parse closes both.
 */
class PositiveDecimalScalarTest {

    private static final GraphQLContext CONTEXT = GraphQLContext.newContext().build();
    private static final Locale LOCALE = Locale.ROOT;

    private final PositiveDecimalScalar scalar = new PositiveDecimalScalar();

    @Test
    void registersUnderThePinnedSaleorScalarName() {
        DgsScalar registration = PositiveDecimalScalar.class.getAnnotation(DgsScalar.class);

        assertThat(registration).isNotNull();
        assertThat(registration.name()).isEqualTo("PositiveDecimal");
    }

    @Test
    void valueToLiteralAcceptsTheStringValuesParseValueAccepts() {
        // The round-9 [23] pin: "1.25" parses through parseValue — the
        // literal conversion must accept the SAME value shape.
        Value<?> literal = scalar.valueToLiteral("1.25", CONTEXT, LOCALE);

        assertThat(literal).isInstanceOf(FloatValue.class);
        assertThat(((FloatValue) literal).getValue()).isEqualByComparingTo("1.25");
        // And the returned literal is valid for parseLiteral (the
        // contract's second half): the engine reading the literal back
        // yields the same exact decimal.
        assertThat(scalar.parseLiteral(literal, null, CONTEXT, LOCALE))
                .isEqualByComparingTo("1.25");
    }

    @Test
    void valueToLiteralPreservesExactIntegerPrecisionBeyondTheDoubleMantissa() {
        // The round-9 [24] pin: 9007199254740993L is one past the double
        // mantissa's exact range — the previous FloatValue.of(double)
        // conversion silently rendered ...992. The shared toDecimal path
        // keeps the exact decimal text.
        Value<?> literal = scalar.valueToLiteral(9007199254740993L, CONTEXT, LOCALE);

        assertThat(literal).isInstanceOf(FloatValue.class);
        assertThat(((FloatValue) literal).getValue())
                .isEqualByComparingTo(new BigDecimal("9007199254740993"));
    }

    @Test
    void valueToLiteralMirrorsParseValueForEveryAcceptedShape() {
        // The same input, both directions, one budget of truth: the
        // BigDecimal, the Integer, the Long and the String shapes all
        // produce the literal parseValue itself would have parsed.
        for (Object input : new Object[] {
                new BigDecimal("19.99"), 42, 9007199254740993L, "0.5"}) {
            Value<?> literal = scalar.valueToLiteral(input, CONTEXT, LOCALE);
            assertThat(scalar.parseLiteral(literal, null, CONTEXT, LOCALE))
                    .as("the literal of %s parses back to the same decimal", input)
                    .isEqualByComparingTo(scalar.parseValue(input, CONTEXT, LOCALE));
        }
    }

    @Test
    void valueToLiteralRejectsNegativeValuesLikeParseValue() {
        assertThatThrownBy(() -> scalar.valueToLiteral("-1.25", CONTEXT, LOCALE))
                .isInstanceOf(CoercingParseValueException.class);
        assertThatThrownBy(() -> scalar.valueToLiteral(new BigDecimal("-0.01"), CONTEXT, LOCALE))
                .isInstanceOf(CoercingParseValueException.class);
    }

    @Test
    void valueToLiteralRejectsNonNumericValuesLikeParseValue() {
        assertThatThrownBy(() -> scalar.valueToLiteral("not-a-number", CONTEXT, LOCALE))
                .isInstanceOf(CoercingParseValueException.class);
        assertThatThrownBy(() -> scalar.valueToLiteral(null, CONTEXT, LOCALE))
                .isInstanceOf(CoercingParseValueException.class);
    }
}
