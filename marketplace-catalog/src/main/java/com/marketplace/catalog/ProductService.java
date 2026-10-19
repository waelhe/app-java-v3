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
    private final ProductVariantRepository productVariantRepository;
    private final StoreCategoryRepository storeCategoryRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ProductTypeAttributeAssignmentRepository assignments;
    private final CatalogAttributeDefinitionRepository definitions;
    private final CatalogAttributeOptionRepository options;
    private final ProductAttributeValueRepository attributeValues;
    private final ProductVariantAttributeValueRepository variantAttributeValues;
    private final com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    public ProductService(ProductRepository productRepository,
                          ProductVariantRepository productVariantRepository,
                          StoreCategoryRepository storeCategoryRepository,
                          ProductTypeRepository productTypeRepository,
                          ProductTypeAttributeAssignmentRepository assignments,
                          CatalogAttributeDefinitionRepository definitions,
                          CatalogAttributeOptionRepository options,
                          ProductAttributeValueRepository attributeValues,
                          ProductVariantAttributeValueRepository variantAttributeValues,
                          com.marketplace.shared.security.CurrentUserProvider currentUserProvider) {
        this.productRepository = productRepository;
        this.productVariantRepository = productVariantRepository;
        this.storeCategoryRepository = storeCategoryRepository;
        this.productTypeRepository = productTypeRepository;
        this.assignments = assignments;
        this.definitions = definitions;
        this.options = options;
        this.attributeValues = attributeValues;
        this.variantAttributeValues = variantAttributeValues;
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
        List<ProductAttributeValue> preparedValues = prepareProductAttributeValues(product, command.attributeValues(), false);
        Product saved = productRepository.save(product);
        if (!preparedValues.isEmpty()) {
            attributeValues.saveAll(preparedValues);
        }
        return saved;
    }

    private List<ProductAttributeValue> prepareProductAttributeValues(
            Product product, List<CatalogAttributeValueInput> suppliedValues, boolean enforceRequired) {
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
            if (enforceRequired && assignment.isRequired() && !seen.contains(assignment.getId())) {
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

    /** The owner's full product roster, including drafts and suspended/archived items. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER')")
    public List<Product> listOwnedProducts(Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        return productRepository.findByProviderId(caller);
    }

    @Transactional
    @PreAuthorize("hasRole('PROVIDER')")
    public Product updateOwnedProduct(UUID productId, UpdateProductCommand command, Authentication authentication) {
        Product product = getOwnedProduct(productId, authentication);
        product.updateDetails(command.title(), command.description(), command.priceMinor());
        return productRepository.save(product);
    }

    @Transactional
    @PreAuthorize("hasRole('PROVIDER')")
    public Product publishOwnedProduct(UUID productId, Authentication authentication) {
        Product product = getOwnedProduct(productId, authentication);
        // ProductType definitions can evolve after draft creation; publication
        // must revalidate current required PRODUCT assignments and choice ownership.
        List<CatalogAttributeValueInput> values = attributeValues
                .findAllByProductIdOrderByAssignmentIdAsc(productId).stream()
                .map(ProductAttributeValue::toInput).toList();
        prepareProductAttributeValues(product, values, true);
        if (product.getProductTypeId() != null) {
            boolean selectionsDefinePurchasableVariants = assignments
                    .findAllByProductTypeIdOrderByScopeAscPositionAsc(product.getProductTypeId()).stream()
                    .anyMatch(assignment -> assignment.getScope() == ProductTypeAttributeScope.VARIANT_SELECTION);
            if (selectionsDefinePurchasableVariants) {
                // The publication gate revalidates EVERY active variant against
                // the CURRENT selection schema (the C1 review's Major finding):
                // an assignment the admin added after the variant's creation
                // must surface HERE, never after publication — a non-null
                // combination key proves nothing about schema currency.
                Set<UUID> requiredSelections = assignments
                        .findAllByProductTypeIdOrderByScopeAscPositionAsc(product.getProductTypeId()).stream()
                        .filter(assignment -> assignment.getScope() == ProductTypeAttributeScope.VARIANT_SELECTION)
                        .map(ProductTypeAttributeAssignment::getId)
                        .collect(java.util.stream.Collectors.toSet());
                boolean hasSelectableVariant = false;
                for (ProductVariant variant : productVariantRepository.findByProductIdOrderBySkuAsc(productId)) {
                    if (variant.getStatus() != ProductStatus.ACTIVE) {
                        continue;
                    }
                    Set<UUID> covered = variantAttributeValues
                            .findAllByProductVariantIdOrderByAssignmentIdAsc(variant.getId()).stream()
                            .map(ProductVariantAttributeValue::getAssignmentId)
                            .collect(java.util.stream.Collectors.toSet());
                    if (!covered.containsAll(requiredSelections)) {
                        throw new ConflictException("Variant '" + variant.getSku()
                                + "' does not satisfy the product type's current variant-selection schema"
                                + " — update it before publication");
                    }
                    if (variant.getOptionCombinationKey() != null) {
                        hasSelectableVariant = true;
                    }
                }
                if (!hasSelectableVariant) {
                    throw new ConflictException(
                            "A ProductType with variant-selection attributes requires at least one active typed variant before publication");
                }
            }
        }
        product.publish();
        return productRepository.save(product);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER')")
    public List<CatalogAttributeValueInput> listOwnedAttributeValues(UUID productId, Authentication authentication) {
        getOwnedProduct(productId, authentication);
        return attributeValues.findAllByProductIdOrderByAssignmentIdAsc(productId).stream()
                .map(ProductAttributeValue::toInput).toList();
    }

    @Transactional
    @PreAuthorize("hasRole('PROVIDER')")
    public List<CatalogAttributeValueInput> replaceOwnedAttributeValues(UUID productId,
            List<CatalogAttributeValueInput> newValues, Authentication authentication) {
        Product product = getOwnedProduct(productId, authentication);
        List<ProductAttributeValue> prepared = prepareProductAttributeValues(product,
                newValues == null ? List.of() : newValues,
                product.getStatus() != StoreProductStatus.DRAFT);
        Map<UUID, ProductAttributeValue> currentByAssignment = new HashMap<>();
        attributeValues.findAllByProductIdOrderByAssignmentIdAsc(productId)
                .forEach(current -> currentByAssignment.put(current.getAssignmentId(), current));
        List<ProductAttributeValue> save = new ArrayList<>();
        Set<UUID> incoming = new HashSet<>();
        for (ProductAttributeValue candidate : prepared) {
            UUID assignmentId = candidate.getAssignmentId();
            incoming.add(assignmentId);
            ProductAttributeValue current = currentByAssignment.get(assignmentId);
            if (current == null) {
                save.add(candidate);
            } else {
                current.replaceValue(candidate.toInput());
                save.add(current);
            }
        }
        List<ProductAttributeValue> removed = currentByAssignment.values().stream()
                .filter(current -> !incoming.contains(current.getAssignmentId())).toList();
        if (!removed.isEmpty()) attributeValues.deleteAll(removed);
        if (!save.isEmpty()) attributeValues.saveAll(save);
        return attributeValues.findAllByProductIdOrderByAssignmentIdAsc(productId).stream()
                .map(ProductAttributeValue::toInput).toList();
    }

    public record UpdateProductCommand(String title, String description, long priceMinor) { }

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
