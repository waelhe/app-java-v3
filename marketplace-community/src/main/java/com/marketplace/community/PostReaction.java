package com.marketplace.community;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * L47 (the Nextdoor-2026 completeness wave — gap #1): one member's
 * single live "thank" on one neighborhood post — the feed's lightest
 * write and Nextdoor's own first signature.
 *
 * <p><b>The domain shape (all gap-analysis decisions, all measured):</b>
 * <ul>
 *   <li>{@code postId} is the plan's sanctioned INTERNAL reference (the
 *       V7 {@code messages.conversation_id} / V61 {@code post_comments}
 *       precedent): a plain UUID column in Java — no JPA relation to
 *       traverse — with a real FK inside the module's own boundary in
 *       V73. The reactions follow their post in the reads (a hidden or
 *       deleted post hides its reactions in the read path without any
 *       physical delete — the same is_deleted semantics the comments
 *       ride).</li>
 *   <li>{@code memberId} is a plain UUID column with NO JPA relation
 *       across module boundaries (the V32/V48/V60 discipline) — it lives
 *       in the users.id space and arrives through the identity seams.</li>
 *   <li>One live reaction per member per post is the product's own
 *       «صوت واحد لكل عضو»: the service's explicit 409 comes first, the
 *       V73 partial unique index is the backstop (the V64 precedent
 *       verbatim), and an un-thank frees the voice for a fresh one.</li>
 *   <li>No enumerated column rides here — the reaction IS the fact, one
 *       shape, no vocabulary to widen.</li>
 * </ul>
 *
 * <p>Every BaseEntity column present from day one (V25/V32 lesson);
 * Hibernate's {@code @SoftDelete} makes the un-thank a soft delete (the
 * row stays — b-5's retention, the Envers trail keeps every thank and
 * un-thank — and the reads stop returning it). The purge seam empties
 * the member's reaction rows when the account closes (b-3).
 */
@Entity
@Table(name = "post_reactions")
@Audited
public class PostReaction extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    protected PostReaction() {
    }

    private PostReaction(UUID id, UUID postId, UUID memberId) {
        this.id = id;
        this.postId = postId;
        this.memberId = memberId;
    }

    /**
     * The thank factory: a fresh live reaction on a VISIBLE post by a
     * member of the post's own neighborhood. Both gates (the post's
     * visibility, the active membership in its location, and the
     * one-voice check) live in the service — before any write; this
     * factory is the honest insert shape.
     */
    public static PostReaction reaction(UUID postId, UUID memberId) {
        return new PostReaction(UUID.randomUUID(), postId, memberId);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getPostId() { return postId; }
    public UUID getMemberId() { return memberId; }
}
