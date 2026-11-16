package com.marketplace.orders;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * The Saleor fit-gap study's Phase 3 (§3.4): one itemized component of the
 * order's frozen monetary breakdown — the AUDITABLE itemization the study
 * demands («record an auditable monetary breakdown and stable order-time
 * agreement; … relevant rule references»). Written once in the placement
 * transaction beside its order, never updated (the snapshot discipline):
 * corrections are cancellations, never edits.
 *
 * <p><b>Types (the closed DB vocabulary):</b> PROMOTION (the best single
 * applicable promotion — {@code referenceId} its row, {@code referenceCode}
 * its name), VOUCHER (the one redeemed voucher — its row and code), TAX
 * (the jurisdiction's policy — {@code referenceCode} carries
 * {@code COUNTRY:MODE:BPS}, {@code referenceId} null: the tax is a policy
 * snapshot, not a row reference). {@code amountMinor} is the component's
 * own magnitude; discounts are recorded as POSITIVE amounts (the order's
 * {@code discount_amount_minor} is their sum).
 */
@Entity
@Table(name = "order_price_components")
@Audited
public class OrderPriceComponent extends BaseEntity {

    public enum ComponentType { PROMOTION, VOUCHER, TAX }

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "component_type", nullable = false, length = 12)
    private ComponentType componentType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "reference_code", length = 120)
    private String referenceCode;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    protected OrderPriceComponent() {
    }

    private OrderPriceComponent(UUID id, UUID orderId, ComponentType componentType,
                                UUID referenceId, String referenceCode,
                                long amountMinor, String currency) {
        this.id = id;
        this.orderId = orderId;
        this.componentType = componentType;
        this.referenceId = referenceId;
        this.referenceCode = referenceCode;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    public static OrderPriceComponent promotionOf(UUID orderId, UUID promotionId, String name,
                                                  long amountMinor, String currency) {
        return new OrderPriceComponent(UUID.randomUUID(), orderId, ComponentType.PROMOTION,
                promotionId, name, amountMinor, currency);
    }

    public static OrderPriceComponent voucherOf(UUID orderId, UUID voucherId, String code,
                                                long amountMinor, String currency) {
        return new OrderPriceComponent(UUID.randomUUID(), orderId, ComponentType.VOUCHER,
                voucherId, code, amountMinor, currency);
    }

    public static OrderPriceComponent taxOf(UUID orderId, String country, String mode, int bps,
                                            long amountMinor, String currency) {
        return new OrderPriceComponent(UUID.randomUUID(), orderId, ComponentType.TAX,
                null, country + ":" + mode + ":" + bps, amountMinor, currency);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public ComponentType getComponentType() {
        return componentType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getReferenceCode() {
        return referenceCode;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }
}
