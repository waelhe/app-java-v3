package com.marketplace.identity;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * assigned-attribute row of a customer type — one live row per
 * (type, attribute) pair, positioned by the {@code position} column the
 * reorder operation rewrites. The {@code attributeId} references the
 * CATALOG module's attribute registry by UUID (no cross-module FK — the
 * Modulith boundary; existence is validated at the composition layer
 * through the catalog module's declared {@code AttributeReadSpi}).
 */
@Entity
@Table(name = "customer_type_attributes")
@Audited
public class CustomerTypeAttribute extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "customer_type_id", nullable = false)
    private UUID customerTypeId;

    @Column(name = "attribute_id", nullable = false)
    private UUID attributeId;

    @Column(name = "position", nullable = false)
    private int position;

    protected CustomerTypeAttribute() {
    }

    private CustomerTypeAttribute(UUID id, UUID customerTypeId, UUID attributeId, int position) {
        this.id = id;
        this.customerTypeId = customerTypeId;
        this.attributeId = attributeId;
        this.position = position;
    }

    public static CustomerTypeAttribute create(UUID customerTypeId, UUID attributeId, int position) {
        return new CustomerTypeAttribute(UUID.randomUUID(), customerTypeId, attributeId, position);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getCustomerTypeId() {
        return customerTypeId;
    }

    public UUID getAttributeId() {
        return attributeId;
    }

    public int getPosition() {
        return position;
    }

    void moveTo(int position) {
        this.position = position;
    }
}
