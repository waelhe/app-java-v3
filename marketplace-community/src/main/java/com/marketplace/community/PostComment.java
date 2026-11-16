package com.marketplace.community;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * L42 (neighborhood community plan §5): a comment on one neighborhood
 * post — the same authored-text shape as the post itself, one aggregate
 * shallower.
 *
 * <p>{@code postId} is the plan's sanctioned INTERNAL reference (the V7
 * {@code messages.conversation_id} precedent, measured): a plain UUID
 * column in Java — no JPA relation to traverse — with a real FK inside
 * the module's own boundary in V61. The comments follow their post in
 * the reads (a hidden or deleted post hides its comments in the read
 * path without any physical delete — the plan's own is_deleted
 * semantics); the comment rows themselves stay (b-5) until their own
 * retention closes.
 *
 * <p>Every BaseEntity column present from day one; the Envers mirror
 * rides V61 (the V24 convention).
 */
@Entity
@Table(name = "post_comments")
@Audited
public class PostComment extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    protected PostComment() {
    }

    private PostComment(UUID id, UUID postId, UUID authorId) {
        this.id = id;
        this.postId = postId;
        this.authorId = authorId;
    }

    /**
     * The comment factory: a fresh comment on a VISIBLE post by a member
     * of the post's own neighborhood. Both gates live in the service
     * (before any write); this factory is the honest insert shape.
     */
    public static PostComment comment(UUID postId, UUID authorId, String body) {
        PostComment comment = new PostComment(UUID.randomUUID(), postId, authorId);
        comment.body = body;
        return comment;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getPostId() { return postId; }
    public UUID getAuthorId() { return authorId; }
    public String getBody() { return body; }
}
