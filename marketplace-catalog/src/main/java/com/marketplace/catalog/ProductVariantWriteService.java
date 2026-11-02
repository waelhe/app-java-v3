package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductVariantBulkOutcome;
import com.marketplace.catalog.spi.ProductVariantBulkUpdateItem;
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

    static final String POLICY_REJECT_EVERYTHING = "REJECT_EVERYTHING";
    static final String POLICY_IGNORE_FAILED = "IGNORE_FAILED";
    static final String POLICY_REJECT_FAILED_ROWS = "REJECT_FAILED_ROWS";

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
        // Row 12 (agent findings 2026-10-26 14:33 export, CWE-203): the
        // ownership decision is resolved FIRST and gates the seller's own
        // namespace queries. The pre-fix flow kept validating after a
        // foreign-parent refusal — the SKU uniqueness check ran against
        // the STRANGER's namespace (parent loaded regardless of
        // ownership), so its ALREADY_EXISTS error disclosed whether a
        // guessed SKU exists in the foreign seller's namespace. The
        // recommendation verbatim: "Return the same product refusal
        // immediately for missing and foreign parents. Perform SKU
        // uniqueness checks only after establishing ownership." The
        // collected-errors shape stays for every OWNED-product failure
        // (the API's own contract); the foreign/missing parent alone ends
        // the evaluation — the stranger's product answers the same
        // REQUIRED refusal shape as an unknown id (never an existence
        // disclosure — the R5 posture), with NOTHING else measured.
        if (productId == null || parent.isEmpty()) {
            return ProductVariantMutationResult.failure(List.of(new ProductVariantMutationError(
                    "product", ProductVariantMutationErrorCode.REQUIRED,
                    "A variant registers under an owning product")));
        }
        if (!parent.get().getProviderId()
                .equals(currentUserProvider.getCurrentUserId(authentication))) {
            return ProductVariantMutationResult.failure(List.of(new ProductVariantMutationError(
                    "product", ProductVariantMutationErrorCode.REQUIRED,
                    "A variant registers under an owning product")));
        }
        String sku = command.sku() == null ? null : command.sku().strip();
        if (sku == null || sku.isBlank()) {
            errors.add(new ProductVariantMutationError("sku", ProductVariantMutationErrorCode.REQUIRED,
                    "A variant requires the seller's SKU"));
        } else if (sku.length() > SKU_MAX_LENGTH) {
            errors.add(new ProductVariantMutationError("sku", ProductVariantMutationErrorCode.INVALID,
                    "A variant SKU is at most " + SKU_MAX_LENGTH + " characters"));
        } else if (variantRepository
                .findByProviderIdAndSku(parent.get().getProviderId(), sku).isPresent()) {
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
            // The wave-19 #808 review (Major · Stability): the parent
            // product's soft delete takes the variant's shelf away — the
            // projection answers the classified NOT_FOUND (the read
            // side's own safe-absence shape), never the parent-absent
            // throw the raw orElseThrow produced.
            return resolveView(addressed.get())
                    .map(ProductVariantMutationResult::success)
                    .orElseGet(ProductVariantWriteService::parentAbsentRefusal);
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
        // The wave-19 #808 review's own law, enforced before any write:
        // the parent's soft delete takes the variant's shelf away — the
        // amendment answers the classified NOT_FOUND (the read side's own
        // safe-absence shape) and never persists an orphan revision.
        if (productRepository.findById(row.getProductId()).isEmpty()) {
            return parentAbsentRefusal();
        }
        row.reviseDetails(mergedSku, mergedName, mergedPriceMinor);
        ProductVariant saved = variantRepository.save(row);
        return resolveView(saved)
                .map(ProductVariantMutationResult::success)
                .orElseGet(ProductVariantWriteService::parentAbsentRefusal);
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
        Optional<ProductVariantView> lastKnown = resolveView(row);
        if (lastKnown.isEmpty()) {
            // The wave-19 #808 review: a variant whose parent product is
            // soft-deleted answers the classified NOT_FOUND — the same
            // safe absence the read surface answers, never a raw throw.
            return parentAbsentRefusal();
        }
        variantRepository.delete(row);
        return ProductVariantMutationResult.success(lastKnown.get());
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

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public List<ProductVariantBulkOutcome> bulkCreateVariants(UUID productId,
                                                              List<ProductVariantCreateCommand> commands,
                                                              Authentication authentication,
                                                              String errorPolicy) {
        List<ProductVariantBulkOutcome> outcomes = new ArrayList<>(commands.size());
        ProductVariantMutationError policyError = unknownPolicyError(errorPolicy);
        if (policyError != null) {
            for (ProductVariantCreateCommand command : commands) {
                outcomes.add(new ProductVariantBulkOutcome(null, List.of(policyError)));
            }
            return outcomes;
        }
        for (ProductVariantCreateCommand command : commands) {
            // Self-invocation joins THIS transaction (the bulk boundary
            // owns the authorization and the policy); the single-item
            // ladder's own validation answers per item.
            ProductVariantMutationResult result = createVariant(
                    new ProductVariantCreateCommand(productId, command.sku(), command.name(),
                            command.priceMinor()),
                    authentication);
            outcomes.add(new ProductVariantBulkOutcome(result.variant(), result.errors()));
        }
        if (atomic(errorPolicy) && outcomes.stream().anyMatch(outcome -> !outcome.success())) {
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            outcomes = rewriteRolledBackOutcomes(outcomes);
        }
        return outcomes;
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public List<ProductVariantBulkOutcome> bulkUpdateVariants(UUID productId,
                                                              List<ProductVariantBulkUpdateItem> items,
                                                              Authentication authentication,
                                                              String errorPolicy) {
        List<ProductVariantBulkOutcome> outcomes = new ArrayList<>(items.size());
        for (ProductVariantBulkUpdateItem item : items) {
            // The shard review (2026-11-01, Minor · Data Integrity): the
            // Saleor product argument is a BINDING ownership claim — an
            // item whose row belongs to another product answers the
            // per-row NOT_FOUND (the safe absence, the same vocabulary
            // ownedRow uses), never a cross-product amendment.
            if (productId == null || variantRepository.findById(item.id())
                    .map(row -> !productId.equals(row.getProductId())).orElse(true)) {
                outcomes.add(new ProductVariantBulkOutcome(null, List.of(
                        new ProductVariantMutationError("product",
                                ProductVariantMutationErrorCode.NOT_FOUND,
                                "The variant does not belong to the addressed product"))));
                continue;
            }
            ProductVariantMutationResult result = updateVariant(item.id(), item.fields(), authentication);
            outcomes.add(new ProductVariantBulkOutcome(result.variant(), result.errors()));
        }
        if (atomic(errorPolicy) && outcomes.stream().anyMatch(outcome -> !outcome.success())) {
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            outcomes = rewriteRolledBackOutcomes(outcomes);
        }
        return outcomes;
    }

    /** The pinned default policy's own verdict — REJECT_EVERYTHING unless another member is sent. */
    private static boolean atomic(String errorPolicy) {
        return errorPolicy == null || POLICY_REJECT_EVERYTHING.equals(errorPolicy);
    }

    /** The unknown-policy refusal — the classified INVALID entry, null for a known member. */
    private static ProductVariantMutationError unknownPolicyError(String errorPolicy) {
        if (errorPolicy == null || POLICY_REJECT_EVERYTHING.equals(errorPolicy)
                || POLICY_IGNORE_FAILED.equals(errorPolicy)
                || POLICY_REJECT_FAILED_ROWS.equals(errorPolicy)) {
            return null;
        }
        return new ProductVariantMutationError("errorPolicy", ProductVariantMutationErrorCode.INVALID,
                "The error policy is REJECT_EVERYTHING, IGNORE_FAILED or REJECT_FAILED_ROWS");
    }

    /** The rollback's outcome rewrite — no phantom projection survives the batch's own refusal. */
    private static List<ProductVariantBulkOutcome> rewriteRolledBackOutcomes(
            List<ProductVariantBulkOutcome> outcomes) {
        List<ProductVariantBulkOutcome> rewritten = new ArrayList<>(outcomes.size());
        for (ProductVariantBulkOutcome outcome : outcomes) {
            if (outcome.success()) {
                rewritten.add(new ProductVariantBulkOutcome(null, List.of(
                        new ProductVariantMutationError("errorPolicy", ProductVariantMutationErrorCode.INVALID,
                                "The batch was rolled back by the REJECT_EVERYTHING policy; this item was not persisted"))));
            } else {
                rewritten.add(outcome);
            }
        }
        return rewritten;
    }

    /** The parent-absent refusal — the classified NOT_FOUND (the read side's safe absence). */
    private static ProductVariantMutationResult parentAbsentRefusal() {
        return ProductVariantMutationResult.failure(List.of(new ProductVariantMutationError(
                "id", ProductVariantMutationErrorCode.NOT_FOUND, "No product variant found for the id")));
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
     * projection, one vocabulary, never a second shape). The wave-19
     * #808 review: the parent's soft delete makes the lookup honestly
     * empty — the answer is the safe absence (an Optional), never an
     * orphan-shelf throw.
     */
    private Optional<ProductVariantView> resolveView(ProductVariant variant) {
        return productRepository.findById(variant.getProductId())
                .map(parent -> toView(variant, parent));
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
