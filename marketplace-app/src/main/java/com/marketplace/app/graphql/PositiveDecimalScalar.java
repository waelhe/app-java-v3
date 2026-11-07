package com.marketplace.app.graphql;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * The platform-owned {@code PositiveDecimal} scalar — Saleor's pinned
 * 3.23.45 money scalar (saleor/graphql/core/scalars.py at the pin,
 * commit {@code e5b7e5f8}: {@code class PositiveDecimal(graphene.Float)},
 * "Nonnegative Decimal scalar implementation. Should be used in places
 * where value must be nonnegative (0 or greater)").
 *
 * <p><b>The pinned wire semantics, mirrored exactly:</b></p>
 * <ul>
 *   <li>Literals: {@code IntValue | FloatValue} only — the pin's
 *       {@code parse_literal} accepts exactly those two node kinds
 *       (a string literal is a coercion failure, never a parsed
 *       value).</li>
 *   <li>Variables: the pin's {@code parse_value} converts the incoming
 *       value through {@code str(value)} before
 *       {@code decimal.Decimal(value)} — so JSON numbers AND numeric
 *       strings both parse, and the string form preserves the decimal
 *       places as typed. This scalar mirrors the discipline: a Java
 *       {@code Double} converts through {@code Double.toString(d)}
 *       (never through binary-float arithmetic), an {@code Integer}/
 *       {@code Long} through its exact decimal text, and a
 *       {@code String} through {@code new BigDecimal(text)}.</li>
 *   <li>Negative values are coercion failures (the pin returns
 *       {@code None} for {@code parsed_value < 0}).</li>
 *   <li>Serialization: the pin rides {@code graphene.Float}, whose
 *       serialize answers a float — "Returns Decimal as a float in the
 *       API" (the pinned Decimal class's own docstring).</li>
 * </ul>
 *
 * <p><b>The frozen money contract (divergence ledger row "Decimal /
 * PositiveDecimal money"): no binary float for authoritative
 * amounts.</b> The exact {@link BigDecimal} this scalar produces is
 * the ONLY value downstream code ever sees — the wire's float shape is
 * transport representation, and the boundary converters (see
 * {@link VoucherWireMoneyConverter}) turn it into the platform's
 * minor-unit/basis-point longs with currency-exact precision before
 * any domain code runs.</p>
 */
@DgsScalar(name = "PositiveDecimal")
public final class PositiveDecimalScalar implements Coercing<BigDecimal, Double> {

    @Override
    public Double serialize(Object dataFetcherResult,
                            GraphQLContext graphQLContext,
                            Locale locale) throws CoercingSerializeException {
        if (dataFetcherResult instanceof BigDecimal value) {
            if (value.signum() < 0) {
                throw new CoercingSerializeException("PositiveDecimal cannot serialize a negative value");
            }
            return value.doubleValue();
        }
        if (dataFetcherResult instanceof Number number) {
            double asDouble = number.doubleValue();
            if (asDouble < 0) {
                throw new CoercingSerializeException("PositiveDecimal cannot serialize a negative value");
            }
            return asDouble;
        }
        throw new CoercingSerializeException("PositiveDecimal requires a decimal value");
    }

    @Override
    public BigDecimal parseValue(Object input,
                                 GraphQLContext graphQLContext,
                                 Locale locale) throws CoercingParseValueException {
        if (input == null) {
            throw new CoercingParseValueException("PositiveDecimal requires a non-null value");
        }
        BigDecimal parsed = toDecimal(input);
        if (parsed.signum() < 0) {
            throw new CoercingParseValueException("PositiveDecimal requires a nonnegative value");
        }
        return parsed;
    }

    @Override
    public BigDecimal parseLiteral(Value<?> input,
                                   CoercedVariables variables,
                                   GraphQLContext graphQLContext,
                                   Locale locale) throws CoercingParseLiteralException {
        if (input instanceof IntValue intValue) {
            BigDecimal parsed = new BigDecimal(intValue.getValue());
            if (parsed.signum() < 0) {
                throw new CoercingParseLiteralException("PositiveDecimal requires a nonnegative value");
            }
            return parsed;
        }
        if (input instanceof FloatValue floatValue) {
            // graphql-java 25.1's FloatValue already carries the exact
            // BigDecimal the parser produced — no binary-float step.
            BigDecimal parsed = floatValue.getValue();
            if (parsed.signum() < 0) {
                throw new CoercingParseLiteralException("PositiveDecimal requires a nonnegative value");
            }
            return parsed;
        }
        throw new CoercingParseLiteralException("PositiveDecimal requires a numeric literal");
    }

    /**
     * The Coercing contract's own closure (graphql-java 25.1,
     * {@code Coercing}: "every valid external input value for parseValue
     * is also valid for valueToLiteral, and the literals returned are
     * valid for parseLiteral") — the same #806-review lesson
     * {@code UuidScalar.valueToLiteral} recorded: the inherited default
     * threw {@code UnsupportedOperationException}. The literal is the
     * exact BigDecimal the engine's own FloatValue carries — the wire's
     * own representation, lossless.
     */
    @Override
    public Value<?> valueToLiteral(Object input,
                                   GraphQLContext graphQLContext,
                                   Locale locale) {
        if (input instanceof BigDecimal value) {
            if (value.signum() < 0) {
                throw new CoercingParseValueException("PositiveDecimal requires a nonnegative value");
            }
            return new FloatValue(value);
        }
        if (input instanceof Number number) {
            double asDouble = number.doubleValue();
            if (asDouble < 0) {
                throw new CoercingParseValueException("PositiveDecimal requires a nonnegative value");
            }
            return FloatValue.of(asDouble);
        }
        throw new CoercingParseValueException("PositiveDecimal requires a decimal value");
    }

    /** The pin's {@code str(value) → Decimal(value)} parse path. */
    private static BigDecimal toDecimal(Object input) throws CoercingParseValueException {
        try {
            if (input instanceof BigDecimal value) {
                return value;
            }
            if (input instanceof Integer || input instanceof Long
                    || input instanceof java.math.BigInteger) {
                return new BigDecimal(input.toString());
            }
            if (input instanceof Float || input instanceof Double) {
                // str(value) first — keeps the decimal places as typed
                // (the pinned Decimal.parse_value's own comment).
                return new BigDecimal(input.toString());
            }
            if (input instanceof String text) {
                return new BigDecimal(text);
            }
            if (input instanceof Number number) {
                return new BigDecimal(number.toString());
            }
        } catch (NumberFormatException exception) {
            throw new CoercingParseValueException("PositiveDecimal requires a decimal value");
        }
        throw new CoercingParseValueException("PositiveDecimal requires a decimal value");
    }
}
