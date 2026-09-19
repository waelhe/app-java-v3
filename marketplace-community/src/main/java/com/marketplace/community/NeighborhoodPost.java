package com.marketplace.community;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Clock;
import java.util.UUID;

/**
 * L42 (neighborhood community plan §5): a member's authored post in
 * exactly one neighborhood — the feed's unit.
 *
 * <p><b>The domain shape (all plan decisions, all measured):</b>
 * <ul>
 *   <li>{@code authorId} and {@code locationId} are plain UUID columns
 *       with NO JPA relation across module boundaries (the V32/V48/V60
 *       discipline) — the author resolves through the identity seams, the
 *       location through {@code GeoLookupPort} (D-N2: level-3 of the ONE
 *       administrative hierarchy, level-checked at publish through the
 *       same L41 gate).</li>
 *   <li>{@code category} is the feed's one filter axis (D-N5); the V61
 *       CHECK pins the SQL membership guard (D-N7). {@code RECOMMENDATION}
 *       is L43's widening point.</li>
 *   <li>{@code title} is bounded at 200 characters — the house
 *       {@code provider_listings.title} limit (V2's own documented
 *       bound); {@code body} is unbounded TEXT, the community domain's
 *       authored text.</li>
 *   <li>{@code status} is {@code VISIBLE} from this layer and flipped by
 *       L45's moderation alone ({@link PostStatus} documents the
 *       reservation); the feed reads VISIBLE only.</li>
 * </ul>
 *
 * <p>Every BaseEntity column present from day one (the V25/V32 lesson);
 * the Envers mirror rides V61 (the V24 convention). The author's own
 * delete is the house soft delete — the row stays (b-5's retention), the
 * reads stop returning it, and the purge seam (b-3) empties the authored
 * text when the account closes.
 */
@Entity
@Table(name = "neighborhood_posts")
@Audited
public class NeighborhoodPost extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    /** The geo tree node — level 3 (neighborhood) only, gated by the service. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private PostCategory category;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PostStatus status;

    protected NeighborhoodPost() {
    }

    private NeighborhoodPost(UUID id, UUID authorId, UUID locationId) {
        this.id = id;
        this.authorId = authorId;
        this.locationId = locationId;
    }

    /**
     * The publish factory: a fresh VISIBLE post. The membership and level
     * gates live in the service (before any write); this factory is the
     * honest insert shape.
     *
     * @param clock the injectable system clock — {@code createdAt} rides
     *              BaseEntity, but the domain's own feed ordering reads
     *              it, so the factory stamps through the same seam every
     *              house domain timestamp rides
     */
    public static NeighborhoodPost post(UUID authorId, UUID locationId,
                                        PostCategory category, String title, String body,
                                        Clock clock) {
        NeighborhoodPost post = new NeighborhoodPost(UUID.randomUUID(), authorId, locationId);
        post.category = category;
        post.title = title;
        post.body = body;
        post.status = PostStatus.VISIBLE;
        return post;
    }

    /**
     * L45 (neighborhood community plan §5 — the moderation &amp; reports
     * layer): the moderation flip — the ONE write to {@code status} in
     * the whole codebase outside the publish factory's own VISIBLE.
     * {@link PostStatus} reserved this transition from L42 ("إخفاء بدون
     * إشراف مستحيل"); the moderation resolve command calls it inside its
     * one transaction so the flip, the report's RESOLVED close and the
     * author's {@code CONTENT_MODERATED} event are atomic (the plan's
     * criterion 2). Package-private by design: the flip is the community
     * domain's own — no surface outside this package can hide a post.
     */
    void hideByModerator() {
        this.status = PostStatus.HIDDEN_BY_MODERATOR;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getAuthorId() { return authorId; }
    public UUID getLocationId() { return locationId; }
    public PostCategory getCategory() { return category; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public PostStatus getStatus() { return status; }
}
