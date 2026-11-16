package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** Assigns reusable attributes to a product type and enforces variant-selection invariants. */
@Service
public class ProductTypeAttributeService {
    private final ProductTypeRepository productTypes;
    private final CatalogAttributeDefinitionRepository definitions;
    private final CatalogAttributeOptionRepository options;
    private final ProductTypeAttributeAssignmentRepository assignments;

    public ProductTypeAttributeService(ProductTypeRepository productTypes,
                                       CatalogAttributeDefinitionRepository definitions,
                                       CatalogAttributeOptionRepository options,
                                       ProductTypeAttributeAssignmentRepository assignments) {
        this.productTypes = productTypes;
        this.definitions = definitions;
        this.options = options;
        this.assignments = assignments;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public List<ProductTypeAttributeAssignment> list(UUID productTypeId) {
        requireProductType(productTypeId);
        return assignments.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId);
    }

    @Observed(name = "catalog.product-type-attribute.assign")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProductTypeAttributeAssignment assign(UUID productTypeId, UUID attributeDefinitionId,
                                                 ProductTypeAttributeScope scope, boolean required, int position) {
        requireProductType(productTypeId);
        CatalogAttributeDefinition definition = definitions.findById(attributeDefinitionId)
                .orElseThrow(() -> new ResourceNotFoundException("CatalogAttributeDefinition", attributeDefinitionId));

        if (scope == ProductTypeAttributeScope.VARIANT_SELECTION) {
            if (!required) {
                throw new ConflictException("VARIANT_SELECTION attributes must be required for every variant");
            }
            if (definition.getValueType() != CatalogAttributeType.CHOICE) {
                throw new ConflictException("VARIANT_SELECTION requires a CHOICE attribute");
            }
            if (options.countByAttributeDefinitionId(attributeDefinitionId) < 2) {
                throw new ConflictException("VARIANT_SELECTION requires at least two allowed options");
            }
        }

        if (assignments.existsByProductTypeIdAndAttributeDefinitionId(productTypeId, attributeDefinitionId)) {
            throw new ConflictException("Attribute is already assigned to this product type");
        }
        if (assignments.existsByProductTypeIdAndScopeAndPosition(productTypeId, scope, position)) {
            throw new ConflictException("Attribute position " + position + " is already used in scope " + scope);
        }
        return assignments.save(ProductTypeAttributeAssignment.create(
                productTypeId, attributeDefinitionId, scope, required, position));
    }

    @Observed(name = "catalog.product-type-attribute.remove")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void remove(UUID assignmentId) {
        ProductTypeAttributeAssignment assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductTypeAttributeAssignment", assignmentId));
        assignments.delete(assignment);
    }

    private void requireProductType(UUID id) {
        productTypes.findById(id).orElseThrow(() -> new ResourceNotFoundException("ProductType", id));
    }
}
