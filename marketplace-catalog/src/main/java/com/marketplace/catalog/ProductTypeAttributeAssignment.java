package com.marketplace.catalog;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/** Assigns a reusable attribute to a product type at product, variant, or customer-selection scope. */
@Entity
@Table(name = "product_type_attribute_assignments")
@Audited
public class ProductTypeAttributeAssignment extends BaseEntity {
    @Id private UUID id;
    @Column(name = "product_type_id", nullable = false) private UUID productTypeId;
    @Column(name = "attribute_definition_id", nullable = false) private UUID attributeDefinitionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 24)
    private ProductTypeAttributeScope scope;
    @Column(name = "is_required", nullable = false) private boolean required;
    @Column(name = "position", nullable = false) private int position;

    protected ProductTypeAttributeAssignment() { }

    private ProductTypeAttributeAssignment(UUID id, UUID productTypeId, UUID attributeDefinitionId,
                                           ProductTypeAttributeScope scope, boolean required, int position) {
        this.id = id; this.productTypeId = productTypeId; this.attributeDefinitionId = attributeDefinitionId;
        this.scope = scope; this.required = required; this.position = position;
    }

    public static ProductTypeAttributeAssignment create(UUID productTypeId, UUID attributeDefinitionId,
                                                        ProductTypeAttributeScope scope, boolean required,
                                                        int position) {
        if (productTypeId == null) throw new IllegalArgumentException("productTypeId is required");
        if (attributeDefinitionId == null) throw new IllegalArgumentException("attributeDefinitionId is required");
        if (scope == null) throw new IllegalArgumentException("scope is required");
        if (position < 0) throw new IllegalArgumentException("position must be non-negative");
        return new ProductTypeAttributeAssignment(UUID.randomUUID(), productTypeId, attributeDefinitionId,
                scope, required, position);
    }

    @Override public UUID getId() { return id; }
    public UUID getProductTypeId() { return productTypeId; }
    public UUID getAttributeDefinitionId() { return attributeDefinitionId; }
    public ProductTypeAttributeScope getScope() { return scope; }
    public boolean isRequired() { return required; }
    public int getPosition() { return position; }
}
