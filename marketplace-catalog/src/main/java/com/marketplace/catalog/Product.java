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
 * A-17 (compliance plan C.7 — the M1 store root): the store's Product —
 * «جذر المتجر», the authoritative product record the orders line's cart
 * snapshots point at and the media line's third target attaches photos to.
 *
 * <p><b>The authoritative pricing (A-11's documented anticipation):</b> the
 * cart's {@code unitAmountMinor} stays the buyer-agreement record the
 * placement freezes (the order's own truth, unchanged); THIS entity is the
 * reference pricing the store's surfaces will read — «التسعير المرجعي يحل
 * محل مصدر المبالغ لا مسار اللقطة», exactly as the orders unit recorded
 * the boundary. The M2 wave (C.8) builds the storefront reading surfaces
 * on top of this root.
 *
 * <p><b>Shape (the V70/Category discipline + the house cross-module
 * convention):</b> {@code storeCategoryCode} references the store
 * categories dictionary by its stable API-facing key — a plain column with
 * a DB-level FK (both land together in V116, so the FK is enforceable from
 * day one; the V70 FK was declared debt only because legacy free-text rows
 * predated its registry). {@code providerId} is the owning provider — a
 * plain UUID column with no JPA relation across module boundaries (the
 * V32/V48/V52/V54/V60/V61/V64 discipline verbatim; the
 * {@code ProductLookupPort} seam resolves it for the media line). The
 * entity is {@code @Audited} per the AGENTS.md rule.
 */
@Entity
@Table(name = "products")
@Audited
public class Product extends BaseEntity {

    @Id
    private UUID id;

    /** The dictionary reference — the stable API-facing key (the V116 FK). */
    @Column(name = "store_category_code", nullable = false, length = 50)
    private String storeCategoryCode;

    /** The product's display title. */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** The product's free-form description (optional at M1). */
    @Column(name = "description", length = 2000)
    private String description;

    /** The authoritative unit price in minor units (the store's own record). */
    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    /** The price's ISO-4217 currency code (the house 3-letter shape). */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** The owning provider — a plain user id, never a JPA relation (the V32 discipline). */
    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    /**
     * Stage 6 (ADR-0002): the store's own stock record — the units on the
     * shelf. The reserved set lives beside it (V172's CHECK pins
     * {@code reserved <= stock} at the database level); placement reserves,
     * fulfillment deducts, cancellation releases — all through the
     * {@code ProductStockPort} seam's conditional updates, never through
     * a read-modify-write here.
     */
    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    /** The units currently frozen by open placements (never read-modify-written here). */
    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    /**
     * The display lifecycle (V172's CHECK membership set). Only ACTIVE
     * products may enter a cart or a storefront read — the suspended and
     * archived states are the buyer-invisible surfaces of that one rule.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "product_status", nullable = false, length = 16)
    private ProductStatus status = ProductStatus.ACTIVE;

    protected Product() {
    }

    private Product(UUID id, String storeCategoryCode, String title, String description,
                    long priceMinor, String currency, UUID providerId) {
        this.id = id;
        this.storeCategoryCode = storeCategoryCode;
        this.title = title;
        this.description = description;
        this.priceMinor = priceMinor;
        this.currency = currency;
        this.providerId = providerId;
        this.stockQuantity = 0;
        this.reservedQuantity = 0;
        this.status = ProductStatus.ACTIVE;
    }

    /**
     * Registers one product — the provider's M1 write path. The category
     * code's dictionary membership is the service's own gate (a 404 on an
     * unknown code, never a silent accept).
     */
    public static Product register(String storeCategoryCode, String title, String description,
                                   long priceMinor, String currency, UUID providerId) {
        return new Product(UUID.randomUUID(), storeCategoryCode, title, description,
                priceMinor, currency, providerId);
    }

    public UUID getId() {
        return id;
    }

    public String getStoreCategoryCode() {
        return storeCategoryCode;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public long getPriceMinor() {
        return priceMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getProviderId() {
        return providerId;
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
     * The provider's inventory write — an absolute shelf count. Lowering
     * below the reserved set is refused here (the same invariant the V172
     * CHECK pins at the database level) so an open placement can never
     * strand.
     */
    public void restock(int quantity) {
        if (quantity < reservedQuantity) {
            throw new IllegalArgumentException(
                    "Stock %d cannot fall below the reserved %d".formatted(quantity, reservedQuantity));
        }
        this.stockQuantity = quantity;
    }

    /** The lifecycle writes — the guarded transitions (the service gates ownership). */
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
            throw new IllegalStateException("Product " + id + " is already ARCHIVED (terminal)");
        }
        this.status = ProductStatus.ARCHIVED;
    }

    private void requireTransition(ProductStatus target) {
        if (status == ProductStatus.ARCHIVED) {
            throw new IllegalStateException("Product " + id + " is ARCHIVED (terminal) — no transition out");
        }
        if (status == target) {
            throw new IllegalStateException("Product " + id + " is already " + target);
        }
    }
}
