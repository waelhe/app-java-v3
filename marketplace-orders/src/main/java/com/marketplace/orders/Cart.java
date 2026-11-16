package com.marketplace.orders;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * A-11 (compliance plan wave C: C.1) — the buyer's shopping cart. One row
 * per consumer per session: V113's partial-unique index
 * ({@code WHERE status = 'ACTIVE' AND is_deleted = FALSE}) guarantees at
 * most one ACTIVE cart, so {@code OrdersService.getOrCreateActiveCart} is
 * idempotent by construction and a concurrent double-create loses to the
 * index exactly the way a duplicate direct-conversation loses to V67
 * (23505 → 409 translation in the house GlobalExceptionHandler).
 *
 * <p>{@code consumerId} is the buyer's USER id (the A1 convention — the
 * users(id) FK follows the measured V6 reviews precedent; identity is the
 * anchor module every cross-module reference keys on).
 */
@Entity
@Table(name = "carts")
@Audited
public class Cart extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "consumer_id", nullable = false)
    private UUID consumerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CartStatus status;

    @Column(name = "checked_out_at")
    private Instant checkedOutAt;

    protected Cart() {
        // JPA
    }

    private Cart(UUID id, UUID consumerId, CartStatus status) {
        this.id = id;
        this.consumerId = consumerId;
        this.status = status;
    }

    public static Cart activeFor(UUID consumerId) {
        return new Cart(UUID.randomUUID(), consumerId, CartStatus.ACTIVE);
    }

    public void checkOut(Instant at) {
        this.status = CartStatus.CHECKED_OUT;
        this.checkedOutAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getConsumerId() {
        return consumerId;
    }

    public CartStatus getStatus() {
        return status;
    }

    public Instant getCheckedOutAt() {
        return checkedOutAt;
    }
}
