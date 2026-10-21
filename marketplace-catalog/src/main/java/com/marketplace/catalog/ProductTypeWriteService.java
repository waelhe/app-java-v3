package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductTypeCreateCommand;
import com.marketplace.catalog.spi.ProductTypeMutationError;
import com.marketplace.catalog.spi.ProductTypeMutationErrorCode;
import com.marketplace.catalog.spi.ProductTypeMutationResult;
import com.marketplace.catalog.spi.ProductTypeUpdateCommand;
import com.marketplace.catalog.spi.ProductTypeView;
import com.marketplace.catalog.spi.ProductTypeWriteSpi;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productTypeCreate}, {@code productTypeUpdate},
 * {@code productTypeDelete} and {@code productTypeBulkDelete} mutation
 * roots, the agent-2 packet): the owning use case for the reusable
 * goods-schema collection's ADMIN write surface. These are Saleor
 * MANAGEMENT roots ("Requires one of the following permissions:
 * MANAGE_PRODUCT_TYPES_AND_ATTRIBUTES") mapped through the platform's
 * ADMIN capability (the same {@code ProductTypeService} register/update
 * precedent the module itself established) and enforced HERE at the
 * owning use-case boundary by Spring Security's method security — never
 * only in a resolver.
 *
 * <p><b>The validation rules are the row's own contracts</b> — the
 * entity factory's own documented shapes: the {@code code} is a
 * lowercase latin slug (the entity's own
 * {@code ^[a-z][a-z0-9-]*$} shape, at most 50 characters), and at
 * least one display name must exist (the bilingual row's own
 * at-least-one contract — the create's Saleor {@code name} binds the
 * name_en dimension). The service re-checks both BEFORE the write (the
 * honest precheck over the derived {@code findByCode} lookup) and lets
 * the column's own uniqueness remain the concurrent-write backstop —
 * the same no-TOCTOU stance the category registry's write service
 * established. Domain validation outcomes answer as result error
 * entries (the Saleor payload-error ADAPT); transport concerns never
 * reach this service.</p>
 *
 * <p><b>Update is partial</b> — {@code null} command fields preserve
 * the column, the Saleor input's omit-to-preserve semantics; an
 * explicit blank name answers the INVALID domain error (the entity's
 * own at-least-one-name law, re-checked over the MERGED display pair).
 * <b>Soft delete</b> rides the {@code BaseEntity} {@code @SoftDelete}
 * discipline: {@code repository.delete} converts to the
 * {@code is_deleted} flag, and the deleted row's last-known projection
 * answers in the payload (Saleor's own delete-payload semantics).</p>
 */
@Service
public class ProductTypeWriteService implements ProductTypeWriteSpi {

    /**
     * The code shape the entity factory itself pins — a lowercase latin
     * slug (mirrored at the boundary so an invalid shape answers the
     * classified INVALID domain error, never an entity throw).
     */
    static final Pattern CODE_SHAPE = Pattern.compile("^[a-z][a-z0-9-]*$");

    /** The row's own column ceilings (the entity's own length contracts). */
    private static final int CODE_MAX_LENGTH = 50;
    private static final int NAME_MAX_LENGTH = 100;

    private final ProductTypeRepository productTypeRepository;

    public ProductTypeWriteService(ProductTypeRepository productTypeRepository) {
        this.productTypeRepository = productTypeRepository;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ProductTypeMutationResult createProductType(ProductTypeCreateCommand command) {
        List<ProductTypeMutationError> errors = new ArrayList<>();
        String name = command.name() == null ? null : command.name().strip();
        String code = command.slug() == null ? null : command.slug().strip().toLowerCase(Locale.ROOT);
        if (name == null || name.isBlank()) {
            errors.add(new ProductTypeMutationError("name", ProductTypeMutationErrorCode.REQUIRED,
                    "A product type name is required"));
        } else if (name.length() > NAME_MAX_LENGTH) {
            errors.add(new ProductTypeMutationError("name", ProductTypeMutationErrorCode.INVALID,
                    "A product type name is at most " + NAME_MAX_LENGTH + " characters"));
        }
        if (code == null || code.isBlank()) {
            errors.add(new ProductTypeMutationError("slug", ProductTypeMutationErrorCode.REQUIRED,
                    "A product type slug is required"));
        } else if (!CODE_SHAPE.matcher(code).matches() || code.length() > CODE_MAX_LENGTH) {
            errors.add(new ProductTypeMutationError("slug", ProductTypeMutationErrorCode.INVALID,
                    "A product type slug is a lowercase latin slug (at most "
                            + CODE_MAX_LENGTH + " characters)"));
        } else if (productTypeRepository.findByCode(code).isPresent()) {
            errors.add(new ProductTypeMutationError("slug", ProductTypeMutationErrorCode.ALREADY_EXISTS,
                    "A product type with the slug already exists"));
        }
        if (!errors.isEmpty()) {
            return ProductTypeMutationResult.failure(errors);
        }
        ProductType created = productTypeRepository.save(
                ProductType.create(code, name, null, null));
        return ProductTypeMutationResult.success(toView(created));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ProductTypeMutationResult updateProductType(UUID id, ProductTypeUpdateCommand command) {
        Optional<ProductType> addressed = id == null
                ? Optional.empty()
                : productTypeRepository.findById(id);
        if (addressed.isEmpty()) {
            return ProductTypeMutationResult.failure(List.of(new ProductTypeMutationError(
                    "id", ProductTypeMutationErrorCode.NOT_FOUND, "No product type found for the id")));
        }
        if (command.isEmpty()) {
            return ProductTypeMutationResult.success(toView(addressed.get()));
        }
        List<ProductTypeMutationError> errors = new ArrayList<>();
        String name = command.name() == null ? null : command.name().strip();
        String code = command.slug() == null ? null : command.slug().strip().toLowerCase(Locale.ROOT);
        if (name != null) {
            if (name.isBlank()) {
                errors.add(new ProductTypeMutationError("name", ProductTypeMutationErrorCode.INVALID,
                        "A product type name cannot be blank"));
            } else if (name.length() > NAME_MAX_LENGTH) {
                errors.add(new ProductTypeMutationError("name", ProductTypeMutationErrorCode.INVALID,
                        "A product type name is at most " + NAME_MAX_LENGTH + " characters"));
            }
        }
        if (code != null) {
            if (!CODE_SHAPE.matcher(code).matches() || code.length() > CODE_MAX_LENGTH) {
                errors.add(new ProductTypeMutationError("slug", ProductTypeMutationErrorCode.INVALID,
                        "A product type slug is a lowercase latin slug (at most "
                                + CODE_MAX_LENGTH + " characters)"));
            } else {
                productTypeRepository.findByCode(code)
                        .filter(existing -> !existing.getId().equals(id))
                        .ifPresent(existing -> errors.add(new ProductTypeMutationError(
                                "slug", ProductTypeMutationErrorCode.ALREADY_EXISTS,
                                "A product type with the slug already exists")));
            }
        }
        if (!errors.isEmpty()) {
            return ProductTypeMutationResult.failure(errors);
        }
        ProductType row = addressed.get();
        // The merged display pair rides the entity's own partial-update
        // law (its at-least-one-name validation runs over the MERGED
        // names, never the command's slice alone).
        String mergedNameEn = name == null ? row.getNameEn() : name;
        row.update(mergedNameEn, row.getNameAr(), row.getDescription());
        ProductType updated = productTypeRepository.save(row);
        return ProductTypeMutationResult.success(toView(updated));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ProductTypeMutationResult deleteProductType(UUID id) {
        Optional<ProductType> addressed = id == null
                ? Optional.empty()
                : productTypeRepository.findById(id);
        if (addressed.isEmpty()) {
            return ProductTypeMutationResult.failure(List.of(new ProductTypeMutationError(
                    "id", ProductTypeMutationErrorCode.NOT_FOUND, "No product type found for the id")));
        }
        ProductType row = addressed.get();
        ProductTypeView lastKnown = toView(row);
        productTypeRepository.delete(row);
        return ProductTypeMutationResult.success(lastKnown);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public int deleteProductTypes(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (UUID id : ids) {
            // Unknown ids are skipped — the honest affected count answers
            // what the registry itself deleted (never a fabricated count,
            // never an error entry for an id the registry never knew).
            Optional<ProductType> addressed = id == null
                    ? Optional.empty()
                    : productTypeRepository.findById(id);
            if (addressed.isPresent()) {
                productTypeRepository.delete(addressed.get());
                deleted++;
            }
        }
        return deleted;
    }

    /** The bounded projection — the read service's own rendering rule. */
    private static ProductTypeView toView(ProductType type) {
        String name = type.getNameEn() != null ? type.getNameEn() : type.getNameAr();
        return new ProductTypeView(type.getId(), name, type.getCode());
    }
}
