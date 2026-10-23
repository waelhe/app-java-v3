package com.marketplace.catalog;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor
 * {@code collectionAddProducts}/{@code collectionRemoveProducts}/
 * {@code collectionReorderProducts} mutation roots, packet agent-2): one
 * row of the collection membership registry — the (collection, product)
 * pair with its own display position.
 *
 * <p>Lifecycle: membership data over the {@code V253} table (the
 * {@code product_type_attribute_assignments} V237 precedent). The pair's
 * LIVE uniqueness is the {@code uq_collection_products_pair_live} partial
 * unique index (a soft-deleted membership releases the pair for a later
 * re-add — the same release-on-delete law the slug carries); the position
 * is the {@code collectionReorderProducts} root's own ordering fact (the
 * membership's own ordering key, the position column's own law). The
 * entity is {@code @Audited} per the AGENTS.md rule, so every membership
 * amendment leaves an Envers trail through the
 * {@code collection_products_aud} mirror.</p>
 */
@Entity
@Table(name = "collection_products")
@Audited
public class CollectionProduct extends BaseEntity {

    @Id
    private UUID id;

    /** The owning collection's native key. */
    @Column(name = "collection_id", nullable = false)
    private UUID collectionId;

    /** The member product's native key. */
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** The display order inside the collection (the reorder root's own fact). */
    @Column(name = "position", nullable = false)
    private int position;

    protected CollectionProduct() {
    }

    private CollectionProduct(UUID id, UUID collectionId, UUID productId, int position) {
        this.id = id;
        this.collectionId = collectionId;
        this.productId = productId;
        this.position = position;
    }

    /** Factory for the owning write use case — the membership's write shape. */
    public static CollectionProduct create(UUID collectionId, UUID productId, int position) {
        return new CollectionProduct(UUID.randomUUID(), collectionId, productId, position);
    }

    @Override
    public UUID getId() {
        return id;
    }

    /**
     * The membership's own ordering amendment — the ADMIN reorder use
     * case's own seam (the Saleor {@code collectionReorderProducts} root
     * mapped onto the membership's position column). The argument is the
     * ALREADY-VALIDATED compacted position (the use case owns the
     * relative-move resolution and the 1..n re-derivation — the
     * {@code ProductTypeAttributeAssignment} reorder precedent).
     */
    void reposition(int position) {
        this.position = position;
    }

    public UUID getCollectionId() {
        return collectionId;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getPosition() {
        return position;
    }
}
