package com.marketplace.community;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L47 — the reaction entity's own factory facts (the PostCommentTest
 * precedent): the factory stamps the id pair verbatim and nothing else;
 * every gate (post visibility, membership match, one-voice) lives in the
 * service before any write.
 */
class PostReactionTest {

    @Test
    void reactionFactory_carriesTheIdPairAndNothingElse() {
        UUID postId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        PostReaction reaction = PostReaction.reaction(postId, memberId);

        assertThat(reaction.getId()).isNotNull();
        assertThat(reaction.getPostId()).isEqualTo(postId);
        assertThat(reaction.getMemberId()).isEqualTo(memberId);
    }

    @Test
    void reactionFactory_freshInstancesAreIndependent() {
        // One voice per member is the repository's unique index, not a
        // shared identity: two factory calls produce two distinct rows —
        // the backstop's own shape.
        UUID postId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        PostReaction first = PostReaction.reaction(postId, memberId);
        PostReaction second = PostReaction.reaction(postId, memberId);

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }
}
