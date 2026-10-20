package com.marketplace.app.graphql;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
 * {@code createdAt} and {@code id}) and adds value normalization:
 * {@code createdAt} parses to {@link Instant} — the declared type on
 * {@code BaseEntity} — and {@code id} to {@link UUID}, accepting either
 * the typed value or its string form (the JSON codec may deliver either),
 * then hands Spring Data a rebuilt position carrying the contract's own
 * types. Unparseable values answer the SAME {@link IllegalArgumentException}
 * the name check always threw, so the exception resolver maps them to the
 * identical VAL-001 error code the cursor taxonomy asserts.</p>
 */
final class KeysetCursorGate {

    private KeysetCursorGate() {
    }

    /**
     * Decodes and normalizes the connection's {@code after} cursor into a
     * forward keyset position over exactly {@code (createdAt: Instant, id: UUID)}.
     *
     * @param cursorStrategy the connection-cursor codec — the SAME
     *                       auto-configured instance both fetchers inject
     *                       ({@code GraphQlDataAutoConfiguration#cursorStrategy})
     * @param after          the opaque cursor; {@code null} answers the
     *                       unpositioned first page
     * @param owner          the connection's own name, carried by the
     *                       validation message exactly as the per-fetcher
     *                       copies always did
     * @return the normalized forward keyset position
     * @throws IllegalArgumentException when the cursor is not a forward
     *                                  {@code (createdAt, id)} keyset cursor — including values that do
     *                                  not parse into the contract's declared types
     */
    static KeysetScrollPosition forwardKeyset(EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                              String after, String owner) {
        if (after == null) {
            return ScrollPosition.keyset();
        }
        ScrollPosition decoded = cursorStrategy.fromCursor(after);
        if (!(decoded instanceof KeysetScrollPosition keyset)
                || !keyset.scrollsForward()
                || !keyset.getKeys().keySet().equals(Set.of("createdAt", "id"))) {
            throw new IllegalArgumentException(
                    "after must be a forward keyset cursor emitted by " + owner);
        }
        try {
            Object createdAt = keyset.getKeys().get("createdAt");
            Object id = keyset.getKeys().get("id");
            Instant createdAtValue = createdAt instanceof Instant value
                    ? value : Instant.parse(String.valueOf(createdAt));
            UUID idValue = id instanceof UUID value ? value : UUID.fromString(String.valueOf(id));
            return ScrollPosition.forward(Map.of("createdAt", createdAtValue, "id", idValue));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "after must be a forward keyset cursor emitted by " + owner, exception);
        }
    }
}
