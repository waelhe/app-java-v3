package com.marketplace.app.graphql;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;

import java.time.Instant;
import java.util.LinkedHashMap;
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
 *
 * <p><b>B18 (CAT-06 — the 2026-11-03 adversarial review, review-pr-808):
 * the cursor's own fingerprint.</b> Key names, value types, and forward
 * direction do not bind a cursor to the scroll that emitted it: a cursor
 * from the categories scroll (sorted {@code name} ASC) passed the
 * collections gate verbatim whenever the key sets coincided, and a cursor
 * from an ASC query passed the same scroll's DESC query — the client then
 * silently received a wrong-page answer instead of the classified
 * validation error every other malformed cursor answers. The gate now
 * stamps every EMITTED cursor with the scroll's fingerprint — the scroll
 * identity, the executed sort property, and the executed sort direction —
 * under the reserved {@code __scroll} key (the double-underscore prefix
 * can never collide with the camelCase entity properties the keyset
 * predicate binds), via {@link #stamped(ScrollPosition, String, String,
 * boolean)} at the connection's edge construction, and REFUSES any
 * {@code after} cursor whose fingerprint is missing or foreign — the
 * categories cursor on the collections scroll, and the ASC cursor on the
 * DESC query, answer the same VAL-001 taxonomy as every other malformed
 * cursor. The rebuilt position handed to Spring Data carries the clean
 * keyset keys only: the fingerprint never reaches the JPA keyset
 * predicate.</p>
 */
final class KeysetCursorGate {

    /**
     * The cursor payload's reserved fingerprint slot — the double-underscore
     * prefix can never collide with the camelCase entity properties the
     * keyset predicate binds (B18/CAT-06).
     */
    private static final String SCROLL_KEY = "__scroll";

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

    /**
     * The NULLABLE text form — the EMAIL vocabulary's own normalizer (the
     * 2026-11-08 #810 round-5 follow-up, CI-measured on the candidate head:
     * the OIDC account with no email claim keeps a NULL email column, and
     * the email-keyed scroll sorts {@code nullsLast}/{@code nullsFirst} so
     * the null block sits at the scroll's own end — a page ending inside
     * that block emits a cursor whose email key carries the null). A null
     * stays null: Spring Data's null-aware keyset predicate (the explicit
     * {@code NullHandling} the owner's Sort carries) turns a null boundary
     * into the {@code IS NULL AND id > ?} prefix leg — the same law the
     * repository's dynamic-sort javadoc documents. {@code String.valueOf}
     * would corrupt the null into the literal string "null".
     */
    static Object nullableTextValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** Numeric sort properties bind as their long form. */
    static Object longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    /**
     * The single-sort form: a forward keyset over exactly
     * {@code (createdAt: Instant, id: UUID)} — the scroll's fixed sort.
     */
    static KeysetScrollPosition forwardKeyset(EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                              String after, String scrollId, boolean ascending) {
        return forwardKeyset(cursorStrategy, after, "createdAt", KeysetCursorGate::instantValue,
                scrollId, ascending);
    }

    /**
     * The emission side of the fingerprint contract (B18/CAT-06): the
     * window position a connection edge would encode, carrying the scroll's
     * fingerprint under the reserved {@code __scroll} key so the gate can
     * refuse it on any other scroll (or any other sort/direction) — the
     * stamped cursor stays opaque to clients, the same strategy encodes
     * it, and the gate strips the fingerprint before Spring Data sees the
     * position.
     */
    static ScrollPosition stamped(ScrollPosition position, String scrollId, String sortProperty,
                                  boolean ascending) {
        if (!(position instanceof KeysetScrollPosition keyset) || !keyset.scrollsForward()) {
            throw new IllegalArgumentException(
                    "the connection edge position must be a forward keyset position to carry "
                            + scrollId + "'s cursor fingerprint");
        }
        Map<String, Object> keys = new LinkedHashMap<>(keyset.getKeys());
        keys.put(SCROLL_KEY, fingerprint(scrollId, sortProperty, ascending));
        return ScrollPosition.forward(keys);
    }

    /**
     * The sort-aware form: a forward keyset over exactly
     * {@code (sortProperty: its declared type, id: UUID)} — the caller owns
     * the property-to-normalizer mapping of its own sort vocabulary — that
     * also carries the scroll's own fingerprint ({@code __scroll}: the
     * scroll identity, the executed sort property, and the executed sort
     * direction), so a cursor emitted by any OTHER scroll, any OTHER sort,
     * or the OPPOSITE direction answers the same classified refusal
     * (B18/CAT-06). The unique-key sort (sortProperty "id" itself — the
     * variant scroll's own ID sort) carries the SINGLE-key vocabulary
     * {id}: the expected key set and the rebuilt position both collapse to
     * the one key (the naive Set.of("id", "id") would throw its own
     * duplicate-element error instead of the classified taxonomy — the gap
     * the variant ID-sort leg measured).
     */
    static KeysetScrollPosition forwardKeyset(EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                              String after, String sortProperty,
                                              Function<Object, Object> sortValue, String scrollId,
                                              boolean ascending) {
        if (after == null) {
            return ScrollPosition.keyset();
        }
        boolean uniqueKeySort = "id".equals(sortProperty);
        Set<String> expectedKeys = uniqueKeySort
                ? Set.of("id", SCROLL_KEY)
                : Set.of(sortProperty, "id", SCROLL_KEY);
        ScrollPosition decoded = cursorStrategy.fromCursor(after);
        if (!(decoded instanceof KeysetScrollPosition keyset)
                || !keyset.scrollsForward()
                || !keyset.getKeys().keySet().equals(expectedKeys)) {
            throw new IllegalArgumentException(
                    "after must be a forward keyset cursor emitted by " + scrollId);
        }
        if (!fingerprint(scrollId, sortProperty, ascending)
                .equals(String.valueOf(keyset.getKeys().get(SCROLL_KEY)))) {
            throw new IllegalArgumentException(
                    "after must be a forward keyset cursor emitted by " + scrollId
                            + " with the same sort and direction");
        }
        try {
            Object idRaw = keyset.getKeys().get("id");
            if (uniqueKeySort) {
                return ScrollPosition.forward(Map.of(
                        "id", uuidValue(idRaw)));
            }
            Object sortRaw = keyset.getKeys().get(sortProperty);
            // The rebuild is NULL-TOLERANT (the 2026-11-08 round-5
            // follow-up, CI-measured on the candidate head): a nullable
            // sort key (the EMAIL vocabulary's null email) must ride the
            // rebuilt position as a GENUINE null — Map.of rejects null
            // values outright, so the normalized pair lands in a
            // LinkedHashMap exactly as the emitted cursor carried it. The
            // caller's own normalizer still runs first (the typed-value
            // contract above); only the EMAIL vocabulary's nullable form
            // answers null. The id tiebreaker keeps its own non-null
            // contract (uuidValue throws on anything else — the classified
            // refusal).
            Object normalized = sortValue.apply(sortRaw);
            java.util.Map<String, Object> rebuilt = new LinkedHashMap<>();
            rebuilt.put(sortProperty, normalized);
            rebuilt.put("id", uuidValue(idRaw));
            return ScrollPosition.forward(rebuilt);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "after must be a forward keyset cursor emitted by " + scrollId, exception);
        }
    }

    /** The scroll's fingerprint: identity, executed sort, executed direction. */
    private static String fingerprint(String scrollId, String sortProperty, boolean ascending) {
        return scrollId + "|" + sortProperty + "|" + (ascending ? "ASC" : "DESC");
    }
}
