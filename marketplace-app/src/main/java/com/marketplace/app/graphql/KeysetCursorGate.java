package com.marketplace.app.graphql;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The connections' ONE keyset-cursor gate — the shared form of what
 * {@code ServiceDataFetcher.keysetPosition} and
 * {@code StoreProductDataFetcher.keysetPosition} each carried as an exact
 * copy until the CodeRabbit round on #677 measured the hole: both copies
 * validated the key NAMES only, so a client-crafted cursor such as
 * {@code {"createdAt":"x","id":42}} passed the key-set check and failed
 * deep inside the JPA keyset predicate as an internal error — while every
 * other malformed cursor answers the VAL-001 validation taxonomy.
 *
 * <p>The gate keeps the measured contract (a forward keyset over exactly
 * the connection's own sort keys) and adds value normalization: each key's
 * value parses to the property's declared type — {@code createdAt} to
 * {@link Instant} (the declared type on {@code BaseEntity}), {@code id} to
 * {@link UUID}, and the sort-aware callers pass their own property
 * normalizer ({@code title}/{@code status} to text, {@code priceMinor} to
 * long) — accepting either the typed value or its string form (the JSON
 * codec may deliver either), then handing Spring Data a rebuilt position
 * carrying the contract's own types. Unparseable values answer the SAME
 * {@link IllegalArgumentException} the name check always threw, so the
 * exception resolver maps them to the identical VAL-001 error code the
 * cursor taxonomy asserts.</p>
 */
final class KeysetCursorGate {

    private KeysetCursorGate() {
    }

    /** {@code createdAt}'s declared type (BaseEntity) — measured. */
    static Object instantValue(Object value) {
        return value instanceof Instant instant ? instant : Instant.parse(String.valueOf(value));
    }

    /** {@code id}'s declared type — the unique tie-breaker on every connection. */
    static Object uuidValue(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    /** Text-typed sort properties bind as their string form. */
    static Object textValue(Object value) {
        return String.valueOf(value);
    }

    /** Numeric sort properties bind as their long form. */
    static Object longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    /**
     * The single-sort form: a forward keyset over exactly
     * {@code (createdAt: Instant, id: UUID)}.
     */
    static KeysetScrollPosition forwardKeyset(EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                              String after, String owner) {
        return forwardKeyset(cursorStrategy, after, "createdAt", KeysetCursorGate::instantValue, owner);
    }

    /**
     * The sort-aware form: a forward keyset over exactly
     * {@code (sortProperty: its declared type, id: UUID)} — the caller owns
     * the property-to-normalizer mapping of its own sort vocabulary. A
     * sort whose property IS the id tie-breaker (the ID sort) carries the
     * single-key keyset {@code (id: UUID)} — the two-key construction
     * would build {@code Set.of("id", "id")} and die on the duplicate
     * element instead of validating the cursor.
     */
    static KeysetScrollPosition forwardKeyset(EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                              String after, String sortProperty,
                                              Function<Object, Object> sortValue, String owner) {
        if (after == null) {
            return ScrollPosition.keyset();
        }
        ScrollPosition decoded = cursorStrategy.fromCursor(after);
        boolean idSorted = "id".equals(sortProperty);
        Set<String> expectedKeys = idSorted ? Set.of("id") : Set.of(sortProperty, "id");
        if (!(decoded instanceof KeysetScrollPosition keyset)
                || !keyset.scrollsForward()
                || !keyset.getKeys().keySet().equals(expectedKeys)) {
            throw new IllegalArgumentException(
                    "after must be a forward keyset cursor emitted by " + owner);
        }
        try {
            Map<String, Object> normalized = new java.util.LinkedHashMap<>(2);
            normalized.put(sortProperty, sortValue.apply(keyset.getKeys().get(sortProperty)));
            if (!idSorted) {
                normalized.put("id", uuidValue(keyset.getKeys().get("id")));
            }
            return ScrollPosition.forward(normalized);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "after must be a forward keyset cursor emitted by " + owner, exception);
        }
    }
}
