package com.marketplace.community;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L42 — the comment entity's factory contract: the comment produces
 * exactly the honest insert shape — the post reference as the plain
 * internal UUID (the V7 messages precedent), the author and body as
 * given.
 */
class PostCommentTest {

    @Test
    void commentFactory_setsEveryStoredFact() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        PostComment comment = PostComment.comment(postId, authorId, "Seen it near the bakery!");

        assertThat(comment.getId()).isNotNull();
        assertThat(comment.getPostId()).isEqualTo(postId);
        assertThat(comment.getAuthorId()).isEqualTo(authorId);
        assertThat(comment.getBody()).isEqualTo("Seen it near the bakery!");
    }
}
