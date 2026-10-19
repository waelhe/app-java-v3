package com.marketplace.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A-17 (compliance plan C.7 — the M1 store root): the provider's product
 * registration and the root's own read — the M1 write path that keeps the
 * root alive (an entity that cannot be created is a schema, not a root).
 *
 * <p><b>The gates (the house shape):</b> registration is PROVIDER-scoped
 * method security with the caller's own id as the owning provider (the
 {@code CurrentUserProvider} seam — the owning provider IS the caller, so
 * no ownership argument can be forged); the category code's dictionary
 * membership is gated BEFORE any write (an unknown code answers the house
 * 404, never a silent accept — the L31 discipline); the read is the
 * owner's own read (the M1 root has no public storefront surface — that
 * arrives with the M2 wave, C.8 — so a stranger's read answers the honest
 * 404, the R5 privacy posture).
 *
 * <p>The commands-not-reads observation policy: the registration is the
 * one observed command ({@code catalog.product.register}); the read stays
 * unobserved (the {@code ObservationCoverageFilesTest} pin discipline).
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final StoreCategoryRepository storeCategoryRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ProductTypeAttributeAssignmentRepository assignments;
    private final CatalogAttributeDefinitionRepository definitions;
    private final CatalogAttributeOptionRepository options;
    private final ProductAttributeValueRepository attributeValues;
    private final com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    public ProductService(ProductRepository productRepository,
                          StoreCategoryRepository storeCategoryRepository,
                          ProductTypeRepository productTypeRepository,
                          ProductTypeAttributeAssignmentRepository assignments,
                          CatalogAttributeDefinitionRepository definitions,
                          CatalogAttributeOptionRepository options,
                          ProductAttributeValueRepository attributeValues,
                          com.marketplace.shared.security.CurrentUserProvider currentUserProvider) {
        this.productRepository = productRepository;
        this.storeCategoryRepository = storeCategoryRepository;
        this.productTypeRepository = productTypeRepository;
        this.assignments = assignments;
        this.definitions = definitions;
        this.options = options;
        this.attributeValues = attributeValues;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Registers one product — the store root's M1 write path. The category
     * code must resolve against the live dictionary (the data-managed
     * vocabulary); the owning provider is the caller.
     */
    @Observed(name = "catalog.product.register")
    @Transactional
    @PreAuthorize("hasRole('PROVIDER')")
    public Product register(RegisterProductCommand command, Authentication authentication) {
        storeCategoryRepository.findByCode(command.storeCategoryCode())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "StoreCategory", command.storeCategoryCode()));
        if (command.productTypeId() != null) {
            productTypeRepository.findById(command.productTypeId())
                    .orElseThrow(() -> new ResourceNotFoundException("ProductType", command.productTypeId()));
        }
        UUID providerId = currentUserProvider.getCurrentUserId(authentication);
        Product product = Product.register(command.storeCategoryCode(), command.title(),
                command.description(), command.priceMinor(), command.currency(), providerId,
                command.productTypeId());
        List<ProductAttributeValue> preparedValues = prepareProductAttributeValues(product, command.attributeValues());
        Product saved = productRepository.save(product);
        if (!preparedValues.isEmpty()) {
            attributeValues.saveAll(preparedValues);
        }
        return saved;
    }

    private List<ProductAttributeValue> prepareProductAttributeValues(
            Product product, List<CatalogAttributeValueInput> suppliedValues) {
        List<CatalogAttributeValueInput> values = suppliedValues == null ? List.of() : suppliedValues;
        if (product.getProductTypeId() == null) {
            if (!values.isEmpty()) {
                throw new ConflictException("Product attribute values require a ProductType");
            }
            return List.of();
        }

        List<ProductTypeAttributeAssignment> productTypeAssignments = assignments
                .findAllByProductTypeIdOrderByScopeAscPositionAsc(product.getProductTypeId());
        Map<UUID, ProductTypeAttributeAssignment> productAssignments = new HashMap<>();
        for (ProductTypeAttributeAssignment assignment : productTypeAssignments) {
            if (assignment.getScope() == ProductTypeAttributeScope.PRODUCT) {
                productAssignments.put(assignment.getAttributeDefinitionId(), assignment);
            }
        }
        Map<UUID, ProductTypeAttributeAssignment> byAssignmentId = new HashMap<>();
        productTypeAssignments.stream().filter(a -> a.getScope() == ProductTypeAttributeScope.PRODUCT)
                .forEach(a -> byAssignmentId.put(a.getId(), a));

        Set<UUID> seen = new HashSet<>();
        List<ProductAttributeValue> prepared = new ArrayList<>();
        for (CatalogAttributeValueInput value : values) {
            if (value == null || value.assignmentId() == null) {
                throw new ConflictException("Every product attribute value requires an assignmentId");
            }
            if (!seen.add(value.assignmentId())) {
                throw new ConflictException("A product attribute assignment may be supplied only once");
            }
            ProductTypeAttributeAssignment assignment = byAssignmentId.get(value.assignmentId());
            if (assignment == null) {
                throw new ConflictException("Attribute assignment does not belong to this ProductType's PRODUCT scope");
            }
            validateTypedValue(assignment, value);
            prepared.add(ProductAttributeValue.create(product.getId(), value));
        }
        for (ProductTypeAttributeAssignment assignment : productAssignments.values()) {
            if (assignment.isRequired() && !seen.contains(assignment.getId())) {
                throw new ConflictException("Required product attribute assignment is missing: " + assignment.getId());
            }
        }
        return List.copyOf(prepared);
    }

    private void validateTypedValue(ProductTypeAttributeAssignment assignment, CatalogAttributeValueInput value) {
        if (value.populatedValueCount() != 1) {
            throw new ConflictException("Exactly one typed value must be supplied for assignment " + assignment.getId());
        }
        CatalogAttributeDefinition definition = definitions.findById(assignment.getAttributeDefinitionId())
                .orElseThrow(() -> new ResourceNotFoundException("CatalogAttributeDefinition", assignment.getAttributeDefinitionId()));
        boolean valid = switch (definition.getValueType()) {
            case TEXT -> value.textValue() != null && value.numberValue() == null
                    && value.booleanValue() == null && value.optionId() == null;
            case NUMBER -> value.textValue() == null && value.numberValue() != null
                    && value.booleanValue() == null && value.optionId() == null;
            case BOOLEAN -> value.textValue() == null && value.numberValue() == null
                    && value.booleanValue() != null && value.optionId() == null;
            case CHOICE -> value.textValue() == null && value.numberValue() == null
                    && value.booleanValue() == null && value.optionId() != null;
        };
        if (!valid) {
            throw new ConflictException("Typed value does not match attribute definition '" + definition.getCode() + "'");
        }
        if (definition.getValueType() == CatalogAttributeType.CHOICE) {
            CatalogAttributeOption option = options.findById(value.optionId())
                    .orElseThrow(() -> new ResourceNotFoundException("CatalogAttributeOption", value.optionId()));
            if (!option.getAttributeDefinitionId().equals(definition.getId())) {
                throw new ConflictException("Choice option does not belong to attribute '" + definition.getCode() + "'");
            }
        }
    }

    /**
     * The owner's read — the M1 root has no public storefront yet (M2's
     * C.8 wave); a stranger's read answers the honest 404 (the R5 posture).
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER')")
    public Product getOwnedProduct(UUID productId, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        if (!product.getProviderId().equals(caller)) {
            // The stranger's read: existence itself is private (the R5 shape).
            throw new ResourceNotFoundException("Product", productId);
        }
        return product;
    }

    /**
     * The registration command — the M1 field set (the authoritative
     * pricing + the dictionary reference + the display text).
     */
    public record RegisterProductCommand(
            String storeCategoryCode,
            String title,
            String description,
            long priceMinor,
            String currency,
            UUID productTypeId,
            List<CatalogAttributeValueInput> attributeValues) {
        public RegisterProductCommand {
            attributeValues = attributeValues == null ? List.of() : List.copyOf(attributeValues);
        }
        /** Source compatibility for callers predating typed ProductType values. */
        public RegisterProductCommand(String storeCategoryCode, String title, String description,
                                      long priceMinor, String currency) {
            this(storeCategoryCode, title, description, priceMinor, currency, null, List.of());
        }
        public RegisterProductCommand(String storeCategoryCode, String title, String description,
                                      long priceMinor, String currency, UUID productTypeId) {
            this(storeCategoryCode, title, description, priceMinor, currency, productTypeId, List.of());
        }
    }
}
