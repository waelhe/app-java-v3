package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductVariantCreateCommand;
import com.marketplace.catalog.spi.ProductVariantMutationError;
import com.marketplace.catalog.spi.ProductVariantMutationErrorCode;
import com.marketplace.catalog.spi.ProductVariantMutationResult;
import com.marketplace.catalog.spi.ProductVariantUpdateCommand;
import com.marketplace.catalog.spi.ProductVariantView;
import com.marketplace.catalog.spi.ProductVariantWriteSpi;
import com.marketplace.catalog.spi.ProductView;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productVariantCreate}, {@code productVariantUpdate},
 * {@code productVariantDelete} and {@code productVariantBulkDelete}
 * mutation roots, the agent-2 packet): the owning use case for the
 * merchandise model's variant write surface (the ADR-0012 registry).
 * These are Saleor MANAGEMENT roots ("Requires one of the following
 * permissions: MANAGE_PRODUCTS") executed over the platform's own
 * ownership law for provider-owned rows — the same law the
 * {@code ProductWriteService} slice established: the owning provider
 * IS the caller (the row's denormalized {@code providerId} — the V219
 * {@code orders.seller_id} precedent — resolves through the
 * {@code CurrentUserProvider} seam), and a stranger's row answers the
 * safe absence (the R5 shape, never an existence disclosure).
 * Authorization is enforced HERE at the owning use-case boundary
 * ({@code @PreAuthorize("hasRole('PROVIDER')")} — the two-sided
 * ownership law), never only in a resolver.
 *
 * <p><b>The validation rules are the row's own contracts</b> — the
 * house shapes the module's own REST registration path pins: the SKU
 * is 1..100 characters (the seller's namespace key — the V219
 * {@code uq_product_variants_provider_sku} unique index), the name at
 * most 200, the price a non-negative minor amount. The service
 * re-checks all of them BEFORE the write; domain outcomes answer as
 * result error entries (the Saleor payload-error ADAPT); transport
 * concerns never reach this service.</p>
 *
 * <p><b>Create copies the ownership facts from the owning product
 * inside the same transaction</b> (the ADR's decision 3 — the
 * provider and the currency are never client-supplied), and the stock
 * starts at zero (the inventory surface is its own root).
 * <b>Update is partial</b> — {@code null} command fields preserve the
 * column, the Saleor input's omit-to-preserve semantics — and the
 * movable set is exactly the row's own amendable facts (the entity's
 * own {@code reviseDetails} seam); an ARCHIVED row is terminal and
 * answers the INVALID domain error (the entity's own documented law,
 * classified here instead of thrown). <b>Soft delete</b> rides the
 * {@code BaseEntity} {@code @SoftDelete} discipline:
 * {@code repository.delete} converts to the {@code is_deleted} flag,
 * and the deleted row's last-known projection answers in the payload
 * (Saleor's own delete-payload semantics).</p>
 */
@Service
public class ProductVariantWriteService implements ProductVariantWriteSpi {

    /** The row's own column ceilings (the entity's own length contracts). */
    private static final int SKU_MAX_LENGTH = 100;
    private static final int NAME_MAX_LENGTH = 200;

    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final CurrentUserProvider currentUserProvider;

    public ProductVariantWriteService(ProductVariantRepository variantRepository,
                                      ProductRepository productRepository,
                                      CurrentUserProvider currentUserProvider) {
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public ProductVariantMutationResult createVariant(ProductVariantCreateCommand command,
                                                      Authentication authentication) {
        List<ProductVariantMutationError> errors = new ArrayList<>();
        UUID productId = command.productId();
        Optional<Product> parent = productId == null
                ? Optional.empty()
                : productRepository.findById(productId);
        if (productId == null || parent.isEmpty()) {
            errors.add(new ProductVariantMutationError("product", ProductVariantMutationErrorCode.REQUIRED,
                    "A variant registers under an owning product"));
        } else if (!parent.get().getProviderId()
                .equals(currentUserProvider.getCurrentUserId(authentication))) {
            // The ownership law's own safe absence: a stranger's product
            // answers the same REQUIRED refusal shape (never an
            // existence disclosure — the R5 posture).
            errors.add(new ProductVariantMutationError("product", ProductVariantMutationErrorCode.REQUIRED,
                    "A variant registers under an owning product"));
        }
        String sku = command.sku() == null ? null : command.sku().strip();
        if (sku == null || sku.isBlank()) {
            errors.add(new ProductVariantMutationError("sku", ProductVariantMutationErrorCode.REQUIRED,
                    "A variant requires the seller's SKU"));
        } else if (sku.length() > SKU_MAX_LENGTH) {
            errors.add(new ProductVariantMutationError("sku", ProductVariantMutationErrorCode.INVALID,
                    "A variant SKU is at most " + SKU_MAX_LENGTH + " characters"));
        } else if (parent.isPresent()
                && variantRepository.findByProviderIdAndSku(parent.get().getProviderId(), sku).isPresent()) {
            errors.add(new ProductVariantMutationError("sku", ProductVariantMutationErrorCode.ALREADY_EXISTS,
                    "The SKU is already used in this seller's namespace"));
        }
        String name = command.name() == null ? null : command.name().strip();
        if (name != null && name.length() > NAME_MAX_LENGTH) {
            errors.add(new ProductVariantMutationError("name", ProductVariantMutationErrorCode.INVALID,
                    "A variant name is at most " + NAME_MAX_LENGTH + " characters"));
        }
        if (command.priceMinor() == null) {
            errors.add(new ProductVariantMutationError("priceMinor", ProductVariantMutationErrorCode.REQUIRED,
                    "A variant requires its unit price"));
        } else if (command.priceMinor() < 0) {
            errors.add(new ProductVariantMutationError("priceMinor", ProductVariantMutationErrorCode.INVALID,
                    "A variant's price is a non-negative minor amount"));
        }
        if (!errors.isEmpty()) {
            return ProductVariantMutationResult.failure(errors);
        }
        ProductVariant created = variantRepository.save(ProductVariant.register(
                parent.get(), sku, name, command.priceMinor()));
        return ProductVariantMutationResult.success(toView(created, parent.get()));
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public ProductVariantMutationResult updateVariant(UUID id, ProductVariantUpdateCommand command,
                                                      Authentication authentication) {
        Optional<ProductVariant> addressed = ownedRow(id, authentication);
        if (addressed.isEmpty()) {
            return ProductVariantMutationResult.failure(List.of(new ProductVariantMutationError(
                    "id", ProductVariantMutationErrorCode.NOT_FOUND, "No product variant found for the id")));
        }
        if (command.isEmpty()) {
            return ProductVariantMutationResult.success(resolveView(addressed.get()));
        }
        ProductVariant row = addressed.get();
        if (row.getStatus() == ProductStatus.ARCHIVED) {
            // The entity's own terminal law, classified as the payload's
            // own INVALID outcome — never an IllegalStateException.
            return ProductVariantMutationResult.failure(List.of(new ProductVariantMutationError(
                    null, ProductVariantMutationErrorCode.INVALID,
                    "Archived product variants are terminal and cannot be edited")));
        }
        List<ProductVariantMutationError> errors = new ArrayList<>();
        String mergedSku = command.sku() == null ? row.getSku() : command.sku().strip();
        if (mergedSku == null || mergedSku.isBlank()) {
            errors.add(new ProductVariantMutationError("sku", command.sku() == null
                    ? ProductVariantMutationErrorCode.REQUIRED : ProductVariantMutationErrorCode.INVALID,
                    "A variant requires the seller's SKU"));
        } else if (mergedSku.length() > SKU_MAX_LENGTH) {
            errors.add(new ProductVariantMutationError("sku", ProductVariantMutationErrorCode.INVALID,
                    "A variant SKU is at most " + SKU_MAX_LENGTH + " characters"));
        } else {
            // The seller's namespace freedom precheck — the V219 unique
            // index remains the concurrent-write backstop (the same
            // no-TOCTOU stance the category code precheck takes).
            variantRepository.findByProviderIdAndSku(row.getProviderId(), mergedSku)
                    .filter(existing -> !existing.getId().equals(id))
                    .ifPresent(existing -> errors.add(new ProductVariantMutationError(
                            "sku", ProductVariantMutationErrorCode.ALREADY_EXISTS,
                            "The SKU is already used in this seller's namespace")));
        }
        String mergedName = command.name() == null ? row.getName() : command.name().strip();
        if (mergedName != null && mergedName.length() > NAME_MAX_LENGTH) {
            errors.add(new ProductVariantMutationError("name", ProductVariantMutationErrorCode.INVALID,
                    "A variant name is at most " + NAME_MAX_LENGTH + " characters"));
        }
        long mergedPriceMinor = command.priceMinor() == null ? row.getPriceMinor() : command.priceMinor();
        if (mergedPriceMinor < 0) {
            errors.add(new ProductVariantMutationError("priceMinor", ProductVariantMutationErrorCode.INVALID,
                    "A variant's price is a non-negative minor amount"));
        }
        if (!errors.isEmpty()) {
            return ProductVariantMutationResult.failure(errors);
        }
        row.reviseDetails(mergedSku, mergedName, mergedPriceMinor);
        return ProductVariantMutationResult.success(resolveView(variantRepository.save(row)));
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public ProductVariantMutationResult deleteVariant(UUID id, Authentication authentication) {
        Optional<ProductVariant> addressed = ownedRow(id, authentication);
        if (addressed.isEmpty()) {
            return ProductVariantMutationResult.failure(List.of(new ProductVariantMutationError(
                    "id", ProductVariantMutationErrorCode.NOT_FOUND, "No product variant found for the id")));
        }
        ProductVariant row = addressed.get();
        ProductVariantView lastKnown = resolveView(row);
        variantRepository.delete(row);
        return ProductVariantMutationResult.success(lastKnown);
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public int deleteVariants(List<UUID> ids, Authentication authentication) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (UUID id : ids) {
            // Unknown or stranger ids are skipped — the honest affected
            // count answers only what the caller owns and the registry
            // itself deleted (never a fabricated count, never an
            // existence disclosure for another provider's row).
            Optional<ProductVariant> addressed = ownedRow(id, authentication);
            if (addressed.isPresent()) {
                variantRepository.delete(addressed.get());
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * The caller's own row — the ownership law's own safe absence: a
     * missing row and a stranger's row answer the same empty (the R5
     * shape, never an existence disclosure — the
     * {@code ProductWriteService.ownedRow} twin).
     */
    private Optional<ProductVariant> ownedRow(UUID id, Authentication authentication) {
        if (id == null) {
            return Optional.empty();
        }
        return variantRepository.findById(id)
                .filter(row -> row.getProviderId()
                        .equals(currentUserProvider.getCurrentUserId(authentication)));
    }

    /**
     * The bounded projection with the parent product's own bounded
     * projection — the read service's own rendering rule (one
     * projection, one vocabulary, never a second shape).
     */
    private ProductVariantView resolveView(ProductVariant variant) {
        Product parent = productRepository.findById(variant.getProductId()).orElseThrow();
        return toView(variant, parent);
    }

    private static ProductVariantView toView(ProductVariant variant, Product parent) {
        return new ProductVariantView(variant.getId(), variant.getName(), variant.getSku(),
                variant.getStatus().name(), toProductView(parent));
    }

    private static ProductView toProductView(Product product) {
        return new ProductView(product.getId(), product.getTitle(),
                product.getProductTypeId(), product.getStatus().name());
    }
}
