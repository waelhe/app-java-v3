package com.marketplace.orders;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * The V225 RMA lifecycle's line: WHICH goods come back and how many — a
 * snapshot of the requested quantity against the order's frozen
 * {@link OrderItem} (the goods' identity is the ORDER's frozen record;
 * this line only carries the return's own quantity).
 */
@Entity
@Table(name = "order_return_lines")
@Audited
public class OrderReturnLine extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "order_return_id", nullable = false)
    private UUID orderReturnId;

    @Column(name = "order_item_id", nullable = false)
    private UUID orderItemId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "variant_id")
    private UUID variantId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected OrderReturnLine() {
    }

    private OrderReturnLine(UUID id, UUID orderReturnId, OrderItem item, int quantity) {
        this.id = id;
        this.orderReturnId = orderReturnId;
        this.orderItemId = item.getId();
        this.productId = item.getProductId();
        this.variantId = item.getVariantId();
        this.quantity = quantity;
    }

    public static OrderReturnLine of(UUID orderReturnId, OrderItem item, int quantity) {
        if (quantity <= 0 || quantity > item.getQuantity()) {
            throw new IllegalArgumentException("A return line's quantity is 1.." + item.getQuantity()
                    + " for the frozen order line, not " + quantity);
        }
        return new OrderReturnLine(UUID.randomUUID(), orderReturnId, item, quantity);
    }

    /** The line's frozen goods value — the refund share's input. */
    public long goodsValueMinor(OrderItem item) {
        return item.getUnitAmountMinor() * (long) quantity;
    }

    public UUID getOrderReturnId() {
        return orderReturnId;
    }

    public UUID getOrderItemId() {
        return orderItemId;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public int getQuantity() {
        return quantity;
    }

    @Override
    public UUID getId() {
        return id;
    }
}
