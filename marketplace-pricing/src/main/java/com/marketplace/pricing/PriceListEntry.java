package com.marketplace.pricing;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * The Saleor fit-gap study's Phase 3: one price-list entry — the unit
 * price ONE purchasable unit carries inside its list. {@code productId}
 * and {@code variantId} are PLAIN UUID columns with NO foreign keys (the
 * V32 cross-module rule: pricing never reads catalog's tables — the ids
 * are opaque, exactly like the cart lines' own product/variant columns
 * beside them); {@code priceListId} is an intra-module DB-level FK (the
 * V219 stance — both tables land in marketplace-pricing).
 *
 * <p>The unique key is the V222 COALESCE union expression
 * {@code (price_list_id, product_id, COALESCE(variant_id, zero-uuid))}:
 * one entry per purchasable unit per list — a product-level entry and a
 * variant entry of the SAME product legally coexist (the variant's own
 * entry wins for variant lines, the product entry serves product-level
 * lines AND variant lines as the documented fallback —
 * {@link #serves(UUID, UUID)}).
 */
@Entity
@Table(name = "price_list_entries")
@Audited
public class PriceListEntry extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "price_list_id", nullable = false)
    private UUID priceListId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** NULL = the product-level entry (the zero-variant rule's line). */
    @Column(name = "variant_id")
    private UUID variantId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    protected PriceListEntry() {
    }

    private PriceListEntry(UUID id, UUID priceListId, UUID productId, UUID variantId, long amountMinor) {
        this.id = id;
        this.priceListId = priceListId;
        this.productId = productId;
        this.variantId = variantId;
        this.amountMinor = amountMinor;
    }

    public static PriceListEntry of(UUID priceListId, UUID productId, UUID variantId, long amountMinor) {
        if (priceListId == null || productId == null) {
            throw new IllegalArgumentException("A price-list entry requires its list and product");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("A price-list entry requires a positive amount");
        }
        return new PriceListEntry(UUID.randomUUID(), priceListId, productId, variantId, amountMinor);
    }

    /**
     * The precedence rule (the port's documented contract): a VARIANT line
     * is served by its own exact entry first, then the product-level
     * entry; a product-level line only by the product-level entry. The
     * union key lets a variant entry and the product entry of the same
     * product coexist in one list — the variant's own entry wins for
     * variant lines.
     */
    public boolean serves(UUID productId, UUID variantId) {
        return this.productId.equals(productId)
                && (java.util.Objects.equals(this.variantId, variantId)
                    || (variantId != null && this.variantId == null));
    }

    /**
     * 0 = the exact entry for the line's unit (the variant's own entry on
     * a variant line); 1 = the product-level fallback. Only meaningful
     * among entries {@link #serves(UUID, UUID)} already accepted.
     */
    public int precedenceRank(UUID variantId) {
        return variantId != null && this.variantId != null ? 0 : 1;
    }

    public void updateAmount(long newAmountMinor) {
        if (newAmountMinor <= 0) {
            throw new IllegalArgumentException("A price-list entry requires a positive amount");
        }
        this.amountMinor = newAmountMinor;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getPriceListId() {
        return priceListId;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }
}
