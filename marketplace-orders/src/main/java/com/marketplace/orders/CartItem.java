package com.marketplace.orders;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * A-11 (compliance plan wave C: C.1) — one line of the cart.
 *
 * <p><b>The {@code productId} boundary (measured, documented, deliberate):</b>
 * the store's product root (compliance plan C.7/M1, unit A-17) does not
 * exist yet, so the product reference is an opaque {@code uuid} with NO
 * foreign key — the Modulith-conformant shape for cross-module references
 * (the module never reads catalog's tables). When M1 lands, the placement
 * flow gains the authoritative price resolution against the real product
 * rows; until then the amount snapshot fields are load-bearing machinery:
 * {@code unitAmountMinor} + {@code currency} travel from the cart line
 * into the immutable order line unchanged, so the snapshot contract (what
 * the buyer agreed to at placement time is what the order forever carries)
 * is pinned by tests from day one and the M1 integration only replaces the
 * SOURCE of the amounts, never their path.
 */
@Entity
@Table(name = "cart_items", uniqueConstraints = @UniqueConstraint(
        name = "uq_cart_items_cart_product", columnNames = {"cart_id", "product_id"}))
@Audited
public class CartItem extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "cart_id", nullable = false)
    private UUID cartId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Min(1)
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_amount_minor", nullable = false)
    private Long unitAmountMinor;

    @NotBlank
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    protected CartItem() {
        // JPA
    }

    private CartItem(UUID id, UUID cartId, UUID productId, Integer quantity,
                     Long unitAmountMinor, String currency) {
        this.id = id;
        this.cartId = cartId;
        this.productId = productId;
        this.quantity = quantity;
        this.unitAmountMinor = unitAmountMinor;
        this.currency = currency;
    }

    public static CartItem of(UUID cartId, UUID productId, Integer quantity,
                              Long unitAmountMinor, String currency) {
        return new CartItem(UUID.randomUUID(), cartId, productId, quantity, unitAmountMinor, currency);
    }

    public void updateQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    /**
     * Stage 6 (ADR-0002): the placement-instant re-pricing write — the
     * authoritative product price resolved fresh at placement replaces
     * the add-instant copy when the store moved its price between the
     * two events (the frozen order line is the placement-instant
     * agreement, never the add-instant one).
     */
    public void updateUnitAmountMinor(Long unitAmountMinor) {
        this.unitAmountMinor = unitAmountMinor;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getCartId() {
        return cartId;
    }

    public UUID getProductId() {
        return productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Long getUnitAmountMinor() {
        return unitAmountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public long lineTotalMinor() {
        return unitAmountMinor * quantity;
    }
}
