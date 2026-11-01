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

    /**
     * The assignment's scope-move seam — the ADMIN assignment-update use
     * case's own seam (the Saleor {@code productAttributeAssignmentUpdate}
     * root mapped onto the row's scope column: flipping the
     * variant-selection flag moves the assignment between the VARIANT and
     * VARIANT_SELECTION scopes). The VARIANT_SELECTION invariants (the
     * CHOICE value shape, the two-option floor, the required-every-
     * variant law) are the SERVICE's own re-checks before this seam —
     * {@code required} rides the scope's own law here (a VARIANT_SELECTION
     * assignment is required for every variant, the platform's own
     * invariant).
     */
    void moveToScope(ProductTypeAttributeScope target) {
        if (target == null) throw new IllegalArgumentException("target scope is required");
        this.scope = target;
        this.required = target == ProductTypeAttributeScope.VARIANT_SELECTION;
    }

    /**
     * The assignment's position-move seam — the ADMIN reorder use case's
     * own seam (the Saleor {@code productTypeReorderAttributes} root).
     * The new position is the SERVICE's own compacted derivation (the
     * relative-move algorithm's outcome); the position-uniqueness column
     * remains the concurrent-write backstop.
     */
    void reposition(int newPosition) {
        if (newPosition < 0) throw new IllegalArgumentException("position must be non-negative");
        this.position = newPosition;
    }

    @Override public UUID getId() { return id; }
    public UUID getProductTypeId() { return productTypeId; }
    public UUID getAttributeDefinitionId() { return attributeDefinitionId; }
    public ProductTypeAttributeScope getScope() { return scope; }
    public boolean isRequired() { return required; }
    public int getPosition() { return position; }
}
