package com.marketplace.lending;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * Stage 8 (plan D-09, ADR-0004): the owner's lending offer — their own
 * terms on their storefront product (the plan's availability/offer
 * projection kept SEPARATE from the confirmed loans). One offer per
 * product (the UNIQUE key), the daily fee in minor units, the optional
 * deposit, and the soft-delete as the withdrawal. Audited (the V24
 * mirror in V174).
 */
@Entity
@Table(name = "lending_offers")
@Audited
public class LendingOffer extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false, unique = true)
    private UUID productId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "daily_fee_minor", nullable = false)
    private long dailyFeeMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "deposit_minor", nullable = false)
    private long depositMinor;

    protected LendingOffer() {
        // JPA
    }

    private LendingOffer(UUID id, UUID productId, UUID ownerId,
                         long dailyFeeMinor, String currency, long depositMinor) {
        this.id = id;
        this.productId = productId;
        this.ownerId = ownerId;
        this.dailyFeeMinor = dailyFeeMinor;
        this.currency = currency;
        this.depositMinor = depositMinor;
    }

    /** The publish write — the ownership gate lives on the service. */
    public static LendingOffer publish(UUID productId, UUID ownerId,
                                       long dailyFeeMinor, String currency, long depositMinor) {
        return new LendingOffer(UUID.randomUUID(), productId, ownerId, dailyFeeMinor, currency, depositMinor);
    }

    public void updateTerms(long dailyFeeMinor, long depositMinor) {
        if (dailyFeeMinor < 0 || depositMinor < 0) {
            throw new IllegalArgumentException("Lending terms are non-negative minor-unit amounts");
        }
        this.dailyFeeMinor = dailyFeeMinor;
        this.depositMinor = depositMinor;
    }

    /** The period fee: the ceiling of the days × the daily rate (a partial day rents the whole day). */
    public long feeFor(java.time.Instant startAt, java.time.Instant endAt) {
        long days = (long) Math.ceil(java.time.Duration.between(startAt, endAt).toMillis() / 86_400_000.0);
        return Math.max(1, days) * dailyFeeMinor;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public long getDailyFeeMinor() {
        return dailyFeeMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public long getDepositMinor() {
        return depositMinor;
    }
}
