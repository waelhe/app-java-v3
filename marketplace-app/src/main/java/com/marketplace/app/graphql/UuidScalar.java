package com.marketplace.app.graphql;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.GraphQLContext;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.CoercedVariables;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The single platform-owned UUID scalar for Saleor wire arguments such as the
 * deprecated Checkout.token compatibility argument. UUID values are transport
 * identifiers only; domain authorization and ownership checks remain in the
 * owning use case.
 *
 * <p>Accepts the canonical 8-4-4-4-12 UUID text form (hex may be upper- or
 * lower-case) and serializes UUID values in Java's canonical lower-case form.
 * It deliberately does not accept shortened forms that UUID.fromString may
 * otherwise tolerate.</p>
 */
@DgsScalar(name = "UUID")
public final class UuidScalar implements Coercing<UUID, String> {

    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    public String serialize(Object dataFetcherResult,
                            GraphQLContext graphQLContext,
                            Locale locale) throws CoercingSerializeException {
        if (dataFetcherResult instanceof UUID uuid) {
            return uuid.toString();
        }
        if (dataFetcherResult instanceof String value) {
            try {
                return parseCanonicalUuid(value).toString();
            } catch (IllegalArgumentException exception) {
                throw new CoercingSerializeException("UUID scalar cannot serialize this value");
            }
        }
        throw new CoercingSerializeException("UUID scalar requires a UUID value");
    }

    @Override
    public UUID parseValue(Object input,
                           GraphQLContext graphQLContext,
                           Locale locale) throws CoercingParseValueException {
        if (!(input instanceof String value)) {
            throw new CoercingParseValueException("UUID scalar requires a string value");
        }
        try {
            return parseCanonicalUuid(value);
        } catch (IllegalArgumentException exception) {
            throw new CoercingParseValueException("UUID scalar requires a canonical UUID value");
        }
    }

    @Override
    public UUID parseLiteral(Value<?> input,
                             CoercedVariables variables,
                             GraphQLContext graphQLContext,
                             Locale locale) throws CoercingParseLiteralException {
        if (!(input instanceof StringValue stringValue)) {
            throw new CoercingParseLiteralException("UUID scalar requires a string literal");
        }
        try {
            return parseCanonicalUuid(stringValue.getValue());
        } catch (IllegalArgumentException exception) {
            throw new CoercingParseLiteralException("UUID scalar requires a canonical UUID value");
        }
    }

    private static UUID parseCanonicalUuid(String value) {
        if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid UUID value");
        }
        return UUID.fromString(value);
    }
}
