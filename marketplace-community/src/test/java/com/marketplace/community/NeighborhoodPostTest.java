package com.marketplace.community;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L42 — the post entity's factory contract: the publish produces exactly
 * the honest insert shape (the stored-facts floor the V61 CHECKs back) —
 * VISIBLE from creation, the category/title/body as given, and nothing
 * this layer writes touches the moderation flip.
 */
class NeighborhoodPostTest {

    private static final Instant FIXED = Instant.parse("2026-09-17T09:30:00Z");
    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

    @Test
    void postFactory_setsEveryStoredFact() {
        UUID authorId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        NeighborhoodPost post = NeighborhoodPost.post(authorId, locationId,
                PostCategory.CLASSIFIED, "Bicycle for sale", "Good condition, rarely used.",
                clock);

        assertThat(post.getId()).isNotNull();
        assertThat(post.getAuthorId()).isEqualTo(authorId);
        assertThat(post.getLocationId()).isEqualTo(locationId);
        assertThat(post.getCategory()).isEqualTo(PostCategory.CLASSIFIED);
        assertThat(post.getTitle()).isEqualTo("Bicycle for sale");
        assertThat(post.getBody()).isEqualTo("Good condition, rarely used.");
        assertThat(post.getStatus()).isEqualTo(PostStatus.VISIBLE);
    }

    @Test
    void theVocabularyCarriesExactlyThePlannedValues() {
        // D-N7: the enumerated columns' vocabularies are pinned by the V61
        // CHECKs — the Java side mirrors them exactly. RECOMMENDATION is
        // L43's widening point; HIDDEN_BY_MODERATOR is L45's flip.
        assertThat(PostCategory.values()).containsExactly(
                PostCategory.GENERAL, PostCategory.CLASSIFIED, PostCategory.LOST_FOUND);
        assertThat(PostStatus.values()).containsExactly(
                PostStatus.VISIBLE, PostStatus.HIDDEN_BY_MODERATOR);
    }
}
