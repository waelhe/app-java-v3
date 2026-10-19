package com.marketplace.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0012 (the merchandise model's variant depth): the seller's variant
 * write/read surface — the {@code ProductService}/{@code
 * ProductInventoryService} shapes verbatim, composed for the variant.
 *
 * <p><b>The gates (the house shape):</b> every write and read gates on
 * the OWNING PRODUCT first — the caller IS the product's provider, or an
 * ADMIN; anyone else answers the honest 404 (existence itself is not
 * public information on this surface, the R5 privacy posture). The SKU
 * namespace is the seller's own ({@code findByProviderIdAndSku} answers
 * the friendly 409; the V219 unique index is the race backstop the
 * global handler maps). The provider and currency are copied from the
 * owning product inside the same transaction — never client-supplied
 * (the ADR's decision 3, the V184 {@code orders.seller_id} precedent).
 *
 * <p>The commands-not-reads observation policy: the registration is the
 * one observed command ({@code catalog.variant.register}); the reads and
 * the inventory/lifecycle writes stay unobserved — the {@code Product}
 * root's own exact treatment (the {@code ObservationCoverageFilesTest}
 * pin discipline).
 */
@Service
public class ProductVariantService {

    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ProductTypeAttributeAssignmentRepository assignments;
    private final CatalogAttributeDefinitionRepository definitions;
    private final CatalogAttributeOptionRepository options;
    private final ProductVariantAttributeValueRepository attributeValues;

    public ProductVariantService(ProductVariantRepository variantRepository,
                                 ProductRepository productRepository,
                                 CurrentUserProvider currentUserProvider,
                                 ProductTypeAttributeAssignmentRepository assignments,
                                 CatalogAttributeDefinitionRepository definitions,
                                 CatalogAttributeOptionRepository options,
                                 ProductVariantAttributeValueRepository attributeValues) {
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.currentUserProvider = currentUserProvider;
        this.assignments = assignments;
        this.definitions = definitions;
        this.options = options;
        this.attributeValues = attributeValues;
    }

    /**
     * Registers one variant under an owned product — the seller's write
     * path. The product must exist and be the caller's (or the caller is
     * an ADMIN); the SKU must be free in the seller's own namespace.
     */
    @Observed(name = "catalog.variant.register")
    @Transactional
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public ProductVariant register(UUID productId, RegisterVariantCommand command,
                                   Authentication authentication) {
        Product product = requireOwnedProduct(productId, authentication);
        UUID providerId = product.getProviderId();
        variantRepository.findByProviderIdAndSku(providerId, command.sku()).ifPresent(existing -> {
            throw new ConflictException("SKU '" + command.sku() + "' is already used by variant "
                    + existing.getId() + " in this seller's namespace");
        });
        List<ProductTypeAttributeAssignment> typeAssignments = product.getProductTypeId() == null
                ? List.of()
                : assignments.findAllByProductTypeIdOrderByScopeAscPositionAsc(product.getProductTypeId());
        List<ValidatedVariantAttribute> preparedValues = validateVariantAttributes(
                product, typeAssignments, command.attributeValues());
        String combinationKey = canonicalCombinationKey(typeAssignments, preparedValues);
        if (combinationKey != null && variantRepository.existsByProductIdAndOptionCombinationKey(productId, combinationKey)) {
            throw new ConflictException("This product already has a variant with the same typed option combination");
        }
        ProductVariant variant = ProductVariant.register(product, command.sku(),
                command.name(), command.priceMinor(), combinationKey);
        ProductVariant saved = variantRepository.save(variant);
        if (!preparedValues.isEmpty()) {
            attributeValues.saveAll(preparedValues.stream()
                    .map(value -> ProductVariantAttributeValue.create(saved.getId(), value.input()))
                    .toList());
        }
        return saved;
    }

    private List<ValidatedVariantAttribute> validateVariantAttributes(
            Product product, List<ProductTypeAttributeAssignment> typeAssignments,
            List<CatalogAttributeValueInput> suppliedValues) {
        List<CatalogAttributeValueInput> values = suppliedValues == null ? List.of() : suppliedValues;
        if (product.getProductTypeId() == null) {
            if (!values.isEmpty()) throw new ConflictException("Variant attribute values require a ProductType");
            return List.of();
        }

        Map<UUID, ProductTypeAttributeAssignment> eligible = new HashMap<>();
        for (ProductTypeAttributeAssignment assignment : typeAssignments) {
            if (assignment.getScope() == ProductTypeAttributeScope.VARIANT
                    || assignment.getScope() == ProductTypeAttributeScope.VARIANT_SELECTION) {
                eligible.put(assignment.getId(), assignment);
            }
        }
        Set<UUID> seen = new HashSet<>();
        List<ValidatedVariantAttribute> prepared = new ArrayList<>();
        for (CatalogAttributeValueInput value : values) {
            if (value == null || value.assignmentId() == null) {
                throw new ConflictException("Every variant attribute value requires an assignmentId");
            }
            if (!seen.add(value.assignmentId())) {
                throw new ConflictException("A variant attribute assignment may be supplied only once");
            }
            ProductTypeAttributeAssignment assignment = eligible.get(value.assignmentId());
            if (assignment == null) {
                throw new ConflictException("Attribute assignment does not belong to this ProductType's VARIANT scope");
            }
            if (value.populatedValueCount() != 1) {
                throw new ConflictException("Exactly one typed value must be supplied for assignment " + assignment.getId());
            }
            CatalogAttributeDefinition definition = definitions.findById(assignment.getAttributeDefinitionId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "CatalogAttributeDefinition", assignment.getAttributeDefinitionId()));
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
            if (assignment.getScope() == ProductTypeAttributeScope.VARIANT_SELECTION
                    && definition.getValueType() != CatalogAttributeType.CHOICE) {
                throw new ConflictException("VARIANT_SELECTION values must be CHOICE attributes");
            }
            if (definition.getValueType() == CatalogAttributeType.CHOICE) {
                CatalogAttributeOption option = options.findById(value.optionId())
                        .orElseThrow(() -> new ResourceNotFoundException("CatalogAttributeOption", value.optionId()));
                if (!option.getAttributeDefinitionId().equals(definition.getId())) {
                    throw new ConflictException("Choice option does not belong to attribute '" + definition.getCode() + "'");
                }
            }
            prepared.add(new ValidatedVariantAttribute(assignment, value));
        }
        for (ProductTypeAttributeAssignment assignment : eligible.values()) {
            if (assignment.isRequired() && !seen.contains(assignment.getId())) {
                throw new ConflictException("Required variant attribute assignment is missing: " + assignment.getId());
            }
        }
        return List.copyOf(prepared);
    }

    /** Null means this product has no typed selectable-option schema (legacy free-form variant path). */
    private String canonicalCombinationKey(List<ProductTypeAttributeAssignment> assignments,
                                           List<ValidatedVariantAttribute> values) {
        List<ProductTypeAttributeAssignment> selectionAssignments = assignments.stream()
                .filter(a -> a.getScope() == ProductTypeAttributeScope.VARIANT_SELECTION).toList();
        if (selectionAssignments.isEmpty()) return null;
        Map<UUID, CatalogAttributeValueInput> byAssignment = new HashMap<>();
        values.forEach(v -> byAssignment.put(v.assignment().getId(), v.input()));
        String canonical = selectionAssignments.stream()
                .filter(a -> byAssignment.containsKey(a.getId()))
                .sorted(Comparator.comparing(a -> a.getId().toString()))
                .map(a -> a.getId() + "=" + byAssignment.get(a.getId()).optionId())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 implementation is required", impossible);
        }
    }

    private record ValidatedVariantAttribute(ProductTypeAttributeAssignment assignment,
                                             CatalogAttributeValueInput input) { }

    /** The owner's roster read — the product's live variants (Sku order, the index's order). */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public List<ProductVariant> listOwnedByProduct(UUID productId, Authentication authentication) {
        requireOwnedProduct(productId, authentication);
        return variantRepository.findByProductIdOrderBySkuAsc(productId);
    }

    /** The owner's read — a stranger's read answers the honest 404 (the R5 posture). */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public ProductVariant getOwnedVariant(UUID variantId, Authentication authentication) {
        return requireOwnedVariant(variantId, authentication);
    }

    /** The shelf write — the absolute stock count (the {@code setStock} twin). */
    @Transactional
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public ProductVariant setStock(UUID variantId, int quantity, Authentication authentication) {
        ProductVariant variant = requireOwnedVariant(variantId, authentication);
        variant.restock(quantity);
        return variantRepository.save(variant);
    }

    /** The availability writes — the entity guards answer the illegal edges. */
    @Transactional
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public ProductVariant suspend(UUID variantId, Authentication authentication) {
        ProductVariant variant = requireOwnedVariant(variantId, authentication);
        variant.suspend();
        return variantRepository.save(variant);
    }

    @Transactional
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public ProductVariant reactivate(UUID variantId, Authentication authentication) {
        ProductVariant variant = requireOwnedVariant(variantId, authentication);
        variant.reactivate();
        return variantRepository.save(variant);
    }

    @Transactional
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public ProductVariant archive(UUID variantId, Authentication authentication) {
        ProductVariant variant = requireOwnedVariant(variantId, authentication);
        variant.archive();
        return variantRepository.save(variant);
    }

    /**
     * The owning-product gate (the {@code ProductInventoryService.requireOwned}
     * shape verbatim): the product's provider or an ADMIN — anyone else
     * answers the honest 404.
     */
    private Product requireOwnedProduct(UUID productId, Authentication authentication) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!product.getProviderId().equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Product", productId);
        }
        return product;
    }

    /** The variant's own ownership gate — rides the owning product's provider (the same shape). */
    private ProductVariant requireOwnedVariant(UUID variantId, Authentication authentication) {
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId));
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!variant.getProviderId().equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("ProductVariant", variantId);
        }
        return variant;
    }

    /**
     * The registration command — the ADR's field set: the seller's SKU,
     * the option's display name, the authoritative unit price. The
     * provider and the currency are the service's own copy from the
     * owning product (never client-supplied); the stock starts at zero
     * and arrives through the inventory surface.
     */
    public record RegisterVariantCommand(String sku, String name, long priceMinor,
                                         List<CatalogAttributeValueInput> attributeValues) {
        public RegisterVariantCommand {
            attributeValues = attributeValues == null ? List.of() : List.copyOf(attributeValues);
        }
        public RegisterVariantCommand(String sku, String name, long priceMinor) {
            this(sku, name, priceMinor, List.of());
        }
    }
}
