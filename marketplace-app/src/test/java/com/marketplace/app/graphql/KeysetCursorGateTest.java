package com.marketplace.app.graphql;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.graphql.data.pagination.CursorEncoder;
import org.springframework.graphql.data.pagination.CursorStrategy;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.graphql.data.query.ScrollPositionCursorStrategy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B18 (CAT-06 — the 2026-11-03 adversarial review, review-pr-808): the
 * cursor fingerprint's own proof. Every emitted cursor carries the scroll's
 * identity, executed sort property, and executed sort direction under the
 * reserved {@code __scroll} key, and the gate refuses any cursor whose
 * fingerprint is missing or foreign with the SAME
 * {@link IllegalArgumentException} the key-set check always threw — the
 * VAL-001 taxonomy the exception resolver asserts downstream.
 *
 * <p>The two acceptance proofs the finding demanded: a categories cursor is
 * refused on the collections scroll, and an ASC cursor is refused on the
 * DESC query. Before the fingerprint, both passed the gate verbatim
 * whenever the key sets coincided and the client silently received a
 * wrong-page answer instead of the classified validation error.</p>
 */
class KeysetCursorGateTest {

    /** The same strategy Boot auto-configures (base64-encoded positions). */
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy =
            CursorStrategy.withEncoder(new ScrollPositionCursorStrategy(), CursorEncoder.base64());

    private static final String CATEGORIES = "categories with the same sortBy";
    private static final String COLLECTIONS = "collections with the same sortBy";

    @Test
    void aStampedCursorRoundTripsThroughItsOwnGate() {
        UUID id = UUID.randomUUID();
        String cursor = cursorStrategy.toCursor(KeysetCursorGate.stamped(
                ScrollPosition.forward(Map.of("name", "Stay", "id", id)),
                CATEGORIES, "name", true));

        KeysetScrollPosition position = KeysetCursorGate.forwardKeyset(
                cursorStrategy, cursor, "name", KeysetCursorGate::textValue, CATEGORIES, true);

        assertThat(position.scrollsForward()).isTrue();
        assertThat(position.getKeys().get("name")).isEqualTo("Stay");
        assertThat(position.getKeys().get("id")).isEqualTo(id);
        // The fingerprint never reaches Spring Data's keyset predicate.
        assertThat(position.getKeys()).doesNotContainKey("__scroll");
    }

    @Test
    void theUniqueKeySortRoundTripsItsSingleKeyFingerprint() {
        UUID variantId = UUID.randomUUID();
        String cursor = cursorStrategy.toCursor(KeysetCursorGate.stamped(
                ScrollPosition.forward(Map.of("id", variantId)),
                "productVariants with the same sortBy", "id", true));

        KeysetScrollPosition position = KeysetCursorGate.forwardKeyset(
                cursorStrategy, cursor, "id", KeysetCursorGate::uuidValue,
                "productVariants with the same sortBy", true);

        assertThat(position.getKeys()).containsOnlyKeys("id");
        assertThat(position.getKeys().get("id")).isEqualTo(variantId);
    }

    @Test
    void aCategoriesCursorIsRefusedOnTheCollectionsScroll() {
        // CAT-06's own measured hole: the categories cursor passed the
        // collections gate verbatim whenever the key sets coincided.
        String categoriesCursor = cursorStrategy.toCursor(KeysetCursorGate.stamped(
                ScrollPosition.forward(Map.of("name", "Stay", "id", UUID.randomUUID())),
                CATEGORIES, "name", true));

        assertThatThrownBy(() -> KeysetCursorGate.forwardKeyset(
                cursorStrategy, categoriesCursor, "name", KeysetCursorGate::textValue,
                COLLECTIONS, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("emitted by " + COLLECTIONS);
    }

    @Test
    void anAscCursorIsRefusedOnTheDescQuery() {
        String ascCursor = cursorStrategy.toCursor(KeysetCursorGate.stamped(
                ScrollPosition.forward(Map.of("name", "Stay", "id", UUID.randomUUID())),
                CATEGORIES, "name", true));

        assertThatThrownBy(() -> KeysetCursorGate.forwardKeyset(
                cursorStrategy, ascCursor, "name", KeysetCursorGate::textValue,
                CATEGORIES, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("emitted by " + CATEGORIES)
                .hasMessageContaining("same sort and direction");
    }

    @Test
    void aCursorFromAnotherSortPropertyIsRefusedOnTheSameScroll() {
        String nameCursor = cursorStrategy.toCursor(KeysetCursorGate.stamped(
                ScrollPosition.forward(Map.of("name", "Stay", "id", UUID.randomUUID())),
                CATEGORIES, "name", true));

        assertThatThrownBy(() -> KeysetCursorGate.forwardKeyset(
                cursorStrategy, nameCursor, "createdAt", KeysetCursorGate::instantValue,
                CATEGORIES, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("emitted by " + CATEGORIES);
    }

    @Test
    void aLegacyUnstampedCursorIsRefused() {
        // A cursor from before the fingerprint (or hand-crafted without it)
        // must not silently pass: the stamp is part of the contract.
        String legacy = cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                "name", "Stay", "id", UUID.randomUUID())));

        assertThatThrownBy(() -> KeysetCursorGate.forwardKeyset(
                cursorStrategy, legacy, "name", KeysetCursorGate::textValue,
                CATEGORIES, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("emitted by " + CATEGORIES);
    }

    @Test
    void aClientCraftedFingerprintValueIsRefusedAsAValidationError() {
        // A well-formed key set carrying a FORGED fingerprint still fails
        // the equality check — the same classified refusal, never a
        // keyset-predicate crash deep inside JPA.
        UUID id = UUID.randomUUID();
        String forged = cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                "name", "Stay", "id", id, "__scroll", CATEGORIES + "|name|ASC")));

        assertThatThrownBy(() -> KeysetCursorGate.forwardKeyset(
                cursorStrategy, forged, "createdAt", KeysetCursorGate::instantValue,
                CATEGORIES, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("emitted by " + CATEGORIES);
    }

    @Test
    void theFixedSortDefaultFormCarriesItsOwnFingerprint() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        String cursor = cursorStrategy.toCursor(KeysetCursorGate.stamped(
                ScrollPosition.forward(Map.of("createdAt", createdAt, "id", id)),
                "services", "createdAt", false));

        KeysetScrollPosition position = KeysetCursorGate.forwardKeyset(
                cursorStrategy, cursor, "services", false);

        assertThat(position.getKeys().get("createdAt")).isEqualTo(createdAt);
        assertThat(position.getKeys().get("id")).isEqualTo(id);

        // And the ASC gate refuses the DESC contract's cursor.
        assertThatThrownBy(() -> KeysetCursorGate.forwardKeyset(
                cursorStrategy, cursor, "services", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same sort and direction");
    }
}
