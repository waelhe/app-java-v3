package com.marketplace.catalog;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * W3 (yelp-level-plan §5 — G19): one member's saved listing — the
 * «حفظ لاحقًا» relation. A favorite is a CLAIMED relation between one
 * member and one listing: one live row per pair (the partial unique
 * index V91 keeps), withdrawn by the house soft delete (the row stays
 * for the audit trail — the Envers mirror records every save/withdraw —
 * and a withdrawn favorite can be re-saved as a fresh row).
 *
 * <p><b>The listing's lifecycle is not the favorite's:</b> a saved
 * listing that later expires, pauses or is withdrawn by its provider
 * stays saved (the relation is the member's own data, b-5's retention
 * discipline); the list view carries the listing's CURRENT status so the
 * member sees the truth. A hard listing deletion does not exist in this
 * system (soft delete everywhere — the FK stays honest).
 */
@Entity
@Table(name = "listing_favorites")
@Audited
public class ListingFavorite extends BaseEntity {

    @Id
    private UUID id;

    /** The saving member — the users.id space (the ownership key). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The saved listing — the FK's own existence guarantee (V91). */
    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    protected ListingFavorite() {
        // JPA
    }

    private ListingFavorite(UUID id, UUID userId, UUID listingId) {
        this.id = id;
        this.userId = userId;
        this.listingId = listingId;
    }

    /** Factory: the member's first save of this listing (the only creator). */
    public static ListingFavorite save(UUID userId, UUID listingId) {
        return new ListingFavorite(UUID.randomUUID(), userId, listingId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getListingId() {
        return listingId;
    }

    public Instant getSavedAt() {
        return getCreatedAt();
    }
}
