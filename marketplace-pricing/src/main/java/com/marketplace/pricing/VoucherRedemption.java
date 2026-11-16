package com.marketplace.pricing;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * The Saleor fit-gap study's Phase 3: one voucher redemption — the WRITE
 * fact that binds a voucher to the order that consumed it. The partial
 * unique index {@code (voucher_id, order_id)} IS the idempotency law (a
 * replayed settle collides instead of double-counting); {@code orderId}
 * is a PLAIN UUID with no FK (the V32 rule — orders is another module).
 * The redemption rows are the voucher's usage ACCOUNTING (limits count
 * rows under the voucher row's lock, never an increment counter).
 */
@Entity
@Table(name = "voucher_redemptions")
@Audited
public class VoucherRedemption extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "voucher_id", nullable = false)
    private UUID voucherId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "consumer_id", nullable = false)
    private UUID consumerId;

    @Column(name = "discount_minor", nullable = false)
    private long discountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt;

    protected VoucherRedemption() {
    }

    private VoucherRedemption(UUID id, UUID voucherId, UUID orderId, UUID consumerId,
                              long discountMinor, String currency, Instant redeemedAt) {
        this.id = id;
        this.voucherId = voucherId;
        this.orderId = orderId;
        this.consumerId = consumerId;
        this.discountMinor = discountMinor;
        this.currency = currency;
        this.redeemedAt = redeemedAt;
    }

    public static VoucherRedemption of(UUID voucherId, UUID orderId, UUID consumerId,
                                       long discountMinor, String currency, Instant at) {
        return new VoucherRedemption(UUID.randomUUID(), voucherId, orderId, consumerId,
                discountMinor, currency, at);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getVoucherId() {
        return voucherId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getConsumerId() {
        return consumerId;
    }

    public long getDiscountMinor() {
        return discountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getRedeemedAt() {
        return redeemedAt;
    }
}
