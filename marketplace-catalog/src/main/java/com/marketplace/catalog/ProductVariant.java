package com.marketplace.catalog;

import java.util.UUID;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

/**
 * ADR-0012 (the merged Saleor study's Phase 2, PR #602): the merchandise
 * model's purchasable unit — the variant under a product. The Saleor
 * separation adopted verbatim: {@link Product} stays the descriptive
 * identity (title, description, category, lifecycle); THIS entity is what
 * a buyer purchases when the product genuinely sells as options — its own
 * SKU, its own authoritative price, its own shelf and reserved counters,
 * its own availability lifecycle.
 *
 * <p><b>The zero-variant rule (the ADR's decision 2):</b> a product with
 * no variants keeps selling exactly as today — the product-level price
 * and stock stay the authoritative record the cart snapshots
 * (ADR-0002's boundary, untouched). No fake variant is ever invented for
 * a unique item (the ADR-0002 no-fake-variants rule, preserved).
 *
 * <p><b>Shape (the V219 discipline + the house conventions):</b>
 * {@code productId} is a plain UUID column with an intra-module DB-level
 * FK (both tables land in marketplace-catalog — the V116
 * {@code store_category} FK stance, NOT the V32 cross-module no-FK rule).
 * {@code providerId} and {@code currency} are denormalized from the
 * owning product at creation inside the same transaction (the V184
 * {@code orders.seller_id} precedent): the provider column carries the
 * seller's SKU namespace (the {@code uq_product_variants_provider_sku}
 * uniqueness), the currency column keeps every variant of one shelf in
 * the shelf's own denomination. The entity is {@code @Audited} per the
 * AGENTS.md rule (the V219 {@code product_variants_aud} mirror).
 */
@Entity
@Table(name = "product_variants")
@Audited
public class ProductVariant extends BaseEntity {

    @Id
    private UUID id;

    /** The owning product — intra-module, the V219 FK (plain column, no JPA relation). */
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** The owning seller — denormalized from the product at creation (the seller's SKU namespace). */
    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    /** The seller's own SKU — unique within the seller's namespace (the V219 unique index). */
    @Column(name = "sku", nullable = false, length = 100)
    private String sku;

    /** The option's display name (e.g. «Large / Red») — retained for legacy and display. */
    @Column(name = "name", length = 200)
    private String name;

    /** SHA-256 of the sorted server-derived selection assignment/option pairs; null for legacy free-form variants. */
    @Column(name = "option_combination_key", length = 64)
    private String optionCombinationKey;

    /** The variant's authoritative unit price in minor units (the ADR's decision 4). */
    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    /** The shelf's own denomination — copied from the product at creation, never client-supplied. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** The variant's own shelf (the V184 stock discipline, mirrored). */
    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    /** The units currently frozen by open placements (never read-modify-written here). */
    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    /** The variant's availability lifecycle (the V184 membership set — buyer-invisible when not ACTIVE). */
    @Enumerated(EnumType.STRING)
    @Column(name = "variant_status", nullable = false, length = 16)
    private ProductStatus status = ProductStatus.ACTIVE;

    protected ProductVariant() {
    }

    private ProductVariant(UUID id, UUID productId, UUID providerId, String sku, String name,
                           long priceMinor, String currency, String optionCombinationKey) {
        this.id = id;
        this.productId = productId;
        this.providerId = providerId;
        this.sku = sku;
        this.name = name;
        this.optionCombinationKey = optionCombinationKey;
        this.priceMinor = priceMinor;
        this.currency = currency;
        this.stockQuantity = 0;
        this.reservedQuantity = 0;
        this.status = ProductStatus.ACTIVE;
    }

    /**
     * Registers one variant under an owned product — the seller's write
     * path. The provider and currency are the SERVICE's own copy from the
     * owning product (never client-supplied — the ADR's decision 3); the
     * stock starts at zero and arrives through the inventory surface (the
     * {@code ProductInventoryService.setStock} twin).
     */
    public static ProductVariant register(Product product, String sku, String name, long priceMinor) {
        return register(product, sku, name, priceMinor, null);
    }

    /** Registers a typed variant using the catalog service's canonical combination key. */
    public static ProductVariant register(Product product, String sku, String name, long priceMinor,
                                          String optionCombinationKey) {
        return new ProductVariant(UUID.randomUUID(), product.getId(), product.getProviderId(),
                sku, name, priceMinor, product.getCurrency(), optionCombinationKey);
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getOptionCombinationKey() {
        return optionCombinationKey;
    }

    public long getPriceMinor() {
        return priceMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public ProductStatus getStatus() {
        return status;
    }

    /**
     * The seller's inventory write — an absolute shelf count (the
     * {@code Product.restock} twin): lowering below the reserved set is
     * refused here (the same invariant the V219 CHECK pins at the
     * database level) so an open placement can never strand.
     */
    public void restock(int quantity) {
        if (quantity < reservedQuantity) {
            throw new IllegalArgumentException(
                    "Stock %d cannot fall below the reserved %d".formatted(quantity, reservedQuantity));
        }
        this.stockQuantity = quantity;
    }

    /**
     * The display amendment — the row's own amendable facts (the
     * {@code Product.updateDetails} twin, the Saleor
     * {@code productVariantUpdate} root's movable set): the SKU, the
     * display name and the authoritative unit price. The ownership
     * facts (productId, providerId, currency — the denormalized
     * copies), the reserved counter and the lifecycle are NOT movable
     * here — each is its own owning capability. The shapes are the
     * SERVICE's own re-checks (the entity's own column contracts);
     * the SKU's namespace freedom is the service's own precheck (the
     * V219 unique index remains the concurrent-write backstop).
     */
    public void reviseDetails(String sku, String name, long priceMinor) {
        this.sku = sku;
        this.name = name;
        this.priceMinor = priceMinor;
    }

    /** The availability writes — the guarded transitions (the service gates ownership). */
    public void suspend() {
        requireTransition(ProductStatus.SUSPENDED);
        this.status = ProductStatus.SUSPENDED;
    }

    public void reactivate() {
        requireTransition(ProductStatus.ACTIVE);
        this.status = ProductStatus.ACTIVE;
    }

    public void archive() {
        if (status == ProductStatus.ARCHIVED) {
            throw new IllegalStateException("ProductVariant " + id + " is already ARCHIVED (terminal)");
        }
        this.status = ProductStatus.ARCHIVED;
    }

    private void requireTransition(ProductStatus target) {
        if (status == ProductStatus.ARCHIVED) {
            throw new IllegalStateException("ProductVariant " + id + " is ARCHIVED (terminal) — no transition out");
        }
        if (status == target) {
            throw new IllegalStateException("ProductVariant " + id + " is already " + target);
        }
    }
}
