package com.marketplace.knowledge;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * B-14 (compliance plan C.4 — the «تعرف على» guide): one community-built
 * knowledge entry about a neighborhood, on the {@code NeighborhoodPost}/
 * {@code Review} house shapes — {@code @Audited} over the full
 * BaseEntity column set from day one, the soft delete keeping the audit
 * trail (the withdraw), born PUBLISHED (the community builds the guide
 * in the open — the create-and-be-seen discipline; every entry is
 * visible from its creation transaction, exactly like a post).
 *
 * <p><b>The seams (all measured house conventions):</b></p>
 * <ul>
 *   <li>{@code authorId} — the entry's AUTHOR ({@code users.id} space,
 *       the A1 convention): the community member who contributed this
 *       piece of the guide. The author owns the entry's edits; the
 *       COMMUNITY owns the guide (many authors, one integrated
 *       directory — the identity statement's own shape).</li>
 *   <li>{@code locationId} — the geo tree node (level 3, the
 *       neighborhood the entry documents — gated by the service through
 *       {@code GeoLookupPort}, the same single administrative hierarchy
 *       every neighborhood anchor rides; a plain UUID column, no JPA
 *       relation across module boundaries — the V32/V48/V54
 *       discipline).</li>
 *   <li>{@code category} — the guide's own vocabulary
 *       ({@link KnowledgeCategory}).</li>
 *   <li>No status machine: the entry's lifecycle is born-published /
 *       editable-by-author / withdrawn-by-soft-delete — the post's own
 *       discipline (the guide has no review queue; the moderation layer
 *       the community module already owns covers abuse reports).</li>
 * </ul>
 */
@Entity
@Table(name = "knowledge_entries")
@Audited
public class KnowledgeEntry extends BaseEntity {

    @Id
    private UUID id;

    /** The contributor's user id (users.id space — the A1 convention). */
    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    /** The geo tree node — level 3 (the neighborhood this entry documents), gated by the service. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private KnowledgeCategory category;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    protected KnowledgeEntry() {
    }

    private KnowledgeEntry(UUID id, UUID authorId, UUID locationId,
                           KnowledgeCategory category, String title, String body) {
        this.id = id;
        this.authorId = authorId;
        this.locationId = locationId;
        this.category = category;
        this.title = title;
        this.body = body;
    }

    /**
     * The contribution factory: born PUBLISHED (the create-and-be-seen
     * discipline — the board returns the entry from the same
     * transaction, and the service publishes the indexing fact on the
     * same boundary). The level-3 geo gate lives in the service (before
     * any write); this factory is the honest insert shape.
     */
    public static KnowledgeEntry contribute(UUID authorId, UUID locationId,
                                            KnowledgeCategory category, String title, String body) {
        return new KnowledgeEntry(UUID.randomUUID(), authorId, locationId, category, title, body);
    }

    /** The author's own edit — the title/category/body the update request carries. */
    public void revise(KnowledgeCategory category, String title, String body) {
        this.category = category;
        this.title = title;
        this.body = body;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getAuthorId() { return authorId; }
    public UUID getLocationId() { return locationId; }
    public KnowledgeCategory getCategory() { return category; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
}
