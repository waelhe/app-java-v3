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
                PostCategory.CLASSIFIED, "Bicycle for sale", "Good condition, rarely used.", false,
                clock);

        assertThat(post.getId()).isNotNull();
        assertThat(post.getAuthorId()).isEqualTo(authorId);
        assertThat(post.getLocationId()).isEqualTo(locationId);
        assertThat(post.getCategory()).isEqualTo(PostCategory.CLASSIFIED);
        assertThat(post.getTitle()).isEqualTo("Bicycle for sale");
        assertThat(post.getBody()).isEqualTo("Good condition, rarely used.");

        assertThat(post.getStatus()).isEqualTo(PostStatus.VISIBLE);
        // ADR-0011 (DSA Art. 26(2)): the undeclared factory form reads false.
        assertThat(post.isDeclaredCommercial()).isFalse();
    }

    @Test
    void theCommercialDeclarationIsTheAuthorsOwnStoredFact() {
        // ADR-0011 (D-15 — DSA Art. 26(2)): the declaration is set ONLY by
        // the publish factory from the author's request — the moderation
        // layer flips status, never this fact.
        UUID authorId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        NeighborhoodPost declared = NeighborhoodPost.post(authorId, locationId,
                PostCategory.CLASSIFIED, "Selling my bike", "Barely used, message me.",
                true, clock);
        assertThat(declared.isDeclaredCommercial()).isTrue();

        NeighborhoodPost undeclared = NeighborhoodPost.post(authorId, locationId,
                PostCategory.GENERAL, "Title", "Body", false, clock);
        assertThat(undeclared.isDeclaredCommercial()).isFalse();
    }

    @Test
    void theVocabularyCarriesExactlyThePlannedValues() {
        // D-N7: the enumerated columns' vocabularies are pinned by the SQL
        // CHECKs — the Java side mirrors them exactly. RECOMMENDATION is
        // L43's widening (V68/V69); HIDDEN_BY_MODERATOR is L45's flip.
        assertThat(PostCategory.values()).containsExactly(
                PostCategory.GENERAL, PostCategory.CLASSIFIED, PostCategory.LOST_FOUND,
                PostCategory.RECOMMENDATION, PostCategory.QUESTION, PostCategory.REQUEST);
        assertThat(PostStatus.values()).containsExactly(
                PostStatus.VISIBLE, PostStatus.HIDDEN_BY_MODERATOR);
    }

    @Test
    void l43_recommendationPostsTakeTheSameFactoryPath() {
        // L43's "byte-for-byte" gate: a RECOMMENDATION post is authored
        // text on the SAME factory — no special-cased branch anywhere in
        // the layer (the plan's §5-L43: the feed, the filters, the
        // comments and the rate limits all ride the axis L42 built).
        UUID authorId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        NeighborhoodPost post = NeighborhoodPost.post(authorId, locationId,
                PostCategory.RECOMMENDATION,
                "Any trustworthy plumber around?",
                "Looking for a reliable plumber for a kitchen leak.", false,
                clock);

        assertThat(post.getCategory()).isEqualTo(PostCategory.RECOMMENDATION);
        assertThat(post.getStatus()).isEqualTo(PostStatus.VISIBLE);
    }
}
