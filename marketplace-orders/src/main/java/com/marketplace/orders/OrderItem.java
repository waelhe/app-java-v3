package com.marketplace.orders;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * A-11 (compliance plan wave C: C.1) — one line of the order: the immutable
 * snapshot of the cart line at placement time. Written once in the
 * placement transaction and never updated (quantity, unit amount and
 * currency are the buyer's agreement record — the whole point of the
 * snapshot); corrections are new orders or cancellations, never edits.
 */
@Entity
@Table(name = "order_items", uniqueConstraints = @UniqueConstraint(
        name = "uq_order_items_order_product", columnNames = {"order_id", "product_id"}))
@Audited
public class OrderItem extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_amount_minor", nullable = false)
    private Long unitAmountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    protected OrderItem() {
        // JPA
    }

    private OrderItem(UUID id, UUID orderId, UUID productId, Integer quantity,
                      Long unitAmountMinor, String currency) {
        this.id = id;
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.unitAmountMinor = unitAmountMinor;
        this.currency = currency;
    }

    public static OrderItem snapshotOf(UUID orderId, CartItem cartLine) {
        return new OrderItem(UUID.randomUUID(), orderId, cartLine.getProductId(),
                cartLine.getQuantity(), cartLine.getUnitAmountMinor(), cartLine.getCurrency());
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
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
