package com.marketplace.catalog;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.util.UUID;

/** One typed value for either a VARIANT fact or a buyer-selectable variant assignment. */
@Entity
@Table(name = "product_variant_attribute_values")
@Audited
public class ProductVariantAttributeValue extends BaseEntity {
    @Id private UUID id;
    @Column(name = "product_variant_id", nullable = false) private UUID productVariantId;
    @Column(name = "assignment_id", nullable = false) private UUID assignmentId;
    @Column(name = "text_value", length = 4000) private String textValue;
    @Column(name = "number_value", precision = 18, scale = 6) private BigDecimal numberValue;
    @Column(name = "boolean_value") private Boolean booleanValue;
    @Column(name = "option_id") private UUID optionId;

    protected ProductVariantAttributeValue() { }

    private ProductVariantAttributeValue(UUID id, UUID productVariantId, UUID assignmentId,
                                         CatalogAttributeValueInput value) {
        this.id = id;
        this.productVariantId = productVariantId;
        this.assignmentId = assignmentId;
        this.textValue = value.textValue();
        this.numberValue = value.numberValue();
        this.booleanValue = value.booleanValue();
        this.optionId = value.optionId();
    }

    public static ProductVariantAttributeValue create(UUID productVariantId, CatalogAttributeValueInput value) {
        if (productVariantId == null || value == null || value.assignmentId() == null) {
            throw new IllegalArgumentException("productVariantId, value, and assignmentId are required");
        }
        if (value.populatedValueCount() != 1) {
            throw new IllegalArgumentException("exactly one typed attribute value must be supplied");
        }
        return new ProductVariantAttributeValue(UUID.randomUUID(), productVariantId, value.assignmentId(), value);
    }

    public void replaceValue(CatalogAttributeValueInput value) {
        if (value == null || !assignmentId.equals(value.assignmentId()) || value.populatedValueCount() != 1) {
            throw new IllegalArgumentException("replacement must carry the same assignment and exactly one typed value");
        }
        this.textValue = value.textValue();
        this.numberValue = value.numberValue();
        this.booleanValue = value.booleanValue();
        this.optionId = value.optionId();
    }

    public CatalogAttributeValueInput toInput() {
        return new CatalogAttributeValueInput(assignmentId, textValue, numberValue, booleanValue, optionId);
    }

    @Override public UUID getId() { return id; }
    public UUID getProductVariantId() { return productVariantId; }
    public UUID getAssignmentId() { return assignmentId; }
    public String getTextValue() { return textValue; }
    public BigDecimal getNumberValue() { return numberValue; }
    public Boolean getBooleanValue() { return booleanValue; }
    public UUID getOptionId() { return optionId; }
}
