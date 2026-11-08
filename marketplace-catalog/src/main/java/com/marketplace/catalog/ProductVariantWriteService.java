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

    /**
     * The 2026-11-08 #808 round-22 review (Minor · Stability &amp;
     * Availability): the module's variant-row LOCK ORDER — the
     * database's own UUID order (PostgreSQL compares uuid values by
     * memcmp, unsigned bytes), which is exactly what the roster read
     * {@code findByProductIdOrderByIdAscForUpdate} locks in via its
     * {@code ORDER BY id}. Java's natural UUID order is NOT the same
     * order: {@link UUID#compareTo} compares the halves as SIGNED
     * longs, so every UUID whose first byte is &gt;= 0x80 (half of all
     * random UUIDs) sorts to the FRONT in Java and to the BACK in the
     * database — a Java-side pass in the natural order and the roster's
     * SQL order are two DIFFERENT orders on the same rows, and the
     * round-21 "one deterministic order" law both claim was never
     * actually met. Every Java-side pass that locks variant rows sorts
     * by the canonical lowercase hex string — byte for byte the
     * database's own memcmp order.
     */
    private static final java.util.Comparator<UUID> VARIANT_LOCK_ORDER =
            java.util.Comparator.comparing(UUID::toString);

    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ProductTypeRepository productTypeRepository;
    private final ProductTypeAttributeAssignmentRepository assignmentRepository;

    public ProductVariantWriteService(ProductVariantRepository variantRepository,
                                      ProductRepository productRepository,
                                      CurrentUserProvider currentUserProvider,
                                      ProductTypeRepository productTypeRepository,
                                      ProductTypeAttributeAssignmentRepository assignmentRepository) {
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.currentUserProvider = currentUserProvider;
        this.productTypeRepository = productTypeRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public ProductVariantMutationResult createVariant(ProductVariantCreateCommand command,
                                                      Authentication authentication) {
        List<ProductVariantMutationError> errors = new ArrayList<>();
        UUID productId = command.productId();
        // The 2026-11-08 #808 round-23 review (Minor · Data Integrity &
        // Integration): the parent read carries a SHARED row lock, held
        // to the transaction's end — the product delete takes the SAME
        // row FOR UPDATE before its guard-plus-cascade, so the pair
        // serializes: either this create commits its variant first and
        // the delete's locked roster sees and cascades it, or the
        // delete commits first and this read answers the honest
        // absence (never a live variant committed under a dead
        // product — the orphan shelf the wave-19 cascade exists to
        // prevent). The unlocked read let the insert ride a parent the
        // delete had already soft-deleted.
        // The 2026-11-08 #808 round-25 review (Minor · Stability &
        // Availability): the ownership predicate rides INSIDE the
        // share-locking query (the new
        // findByIdAndProviderIdForShare — the round-21 scoped-lock law's
        // FOR-SHARE twin) — this mutation path is provider-only (no
        // ADMIN exception below), so a stranger's product row is skipped
        // without ever taking its share lock: the request answers the
        // same REQUIRED refusal immediately, never waiting on the
        // foreign row's lock before the refusal. The delete-wall pairing
        // above is unchanged for the caller's OWN row.
        UUID createCaller = currentUserProvider.getCurrentUserId(authentication);
        Optional<Product> parent = productId == null || createCaller == null
                ? Optional.empty()
                : productRepository.findByIdAndProviderIdForShare(productId, createCaller);
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
        if (parent.isEmpty()) {
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
                .countSkuReservationsIncludingDeleted(parent.get().getProviderId(), sku) > 0) {
            // The 2026-11-06 #808 round (Minor · Data Integrity): the
            // SKU reservation spans the RAW table — V219's unique index
            // is GLOBAL (not partial over the live rows), so a
            // soft-deleted variant keeps reserving its SKU while the
            // derived probe (filtered to live rows) misses it and the
            // write dies on the raw constraint error instead of the
            // classified ALREADY_EXISTS. The native count sees every
            // reservation — live AND soft-deleted.
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
        // The 2026-11-06 #808 round-3 review (Major · Data Integrity &amp;
        // Integration): the typed-schema eligibility gate — this root's
        // command carries NO attribute values (the bounded field set the
        // record documents), so a product whose type schema DEMANDS values
        // (a VARIANT_SELECTION assignment — always required — or a
        // required VARIANT assignment; the registration path's own
        // zero-values eligibility law) cannot take its variant here: the
        // insert would land an ACTIVE row with a null combination key the
        // partial unique index ignores (no combination uniqueness),
        // verifySelectionConsistency skips, and the publication gate then
        // blocks the whole product while that variant stays active. The
        // typed path (ProductVariantService.register — the command that
        // carries the values and derives the key) owns those variants.
        // The share-lock discipline is the register path's own: the type
        // row is read FOR SHARE (held to commit — an updateAssignments
        // move serializes against this read), and a row that answers
        // empty (a withdrawn schema) owns no live assignments — the free
        // form stays eligible exactly as register answers it.
        if (parent.get().getProductTypeId() != null) {
            productTypeRepository.findByIdForShare(parent.get().getProductTypeId());
            boolean schemaDemandsValues = assignmentRepository
                    .findAllByProductTypeIdOrderByScopeAscPositionAsc(parent.get().getProductTypeId())
                    .stream()
                    .anyMatch(assignment -> assignment.getScope() == ProductTypeAttributeScope.VARIANT_SELECTION
                            || assignment.isRequired());
            if (schemaDemandsValues) {
                return ProductVariantMutationResult.failure(List.of(new ProductVariantMutationError(
                        "product", ProductVariantMutationErrorCode.INVALID,
                        "The product's type demands typed attribute values for every variant — register the"
                                + " variant with its attribute values through the typed registration path")));
            }
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
        // The 2026-11-05 #808 round (Major · Data Integrity): the update
        // rides the LOCKED ownership read — the plain findById raced a
        // concurrent placement's reservation or a delete's wall and a
        // lost update (reviseDetails + save over a row another
        // transaction moved): the same ownedRowForUpdate the delete
        // paths ride.
        Optional<ProductVariant> addressed = ownedRowForUpdate(id, authentication);
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
            // The 2026-11-06 #808 round (Minor · Data Integrity): the
            // reservation spans the RAW table (soft-deleted rows keep
            // their SKU), and the update leg excludes the addressed
            // row itself — the same-sku re-send stays the idempotent
            // no-op.
            if (variantRepository.countSkuReservationsIncludingDeletedExcluding(
                    row.getProviderId(), mergedSku, id) > 0) {
                errors.add(new ProductVariantMutationError(
                        "sku", ProductVariantMutationErrorCode.ALREADY_EXISTS,
                        "The SKU is already used in this seller's namespace"));
            }
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
        // The 2026-11-05 #808 round (Major · Functional Correctness): the
        // row is loaded THROUGH the pessimistic locked read — the variant
        // reservation is a native UPDATE on this row alone, so the lock
        // serializes the wall below against a concurrent placement (the
        // product delete's own B7-wall pattern, the single-row form).
        Optional<ProductVariant> addressed = ownedRowForUpdate(id, authentication);
        if (addressed.isEmpty()) {
            return ProductVariantMutationResult.failure(List.of(new ProductVariantMutationError(
                    "id", ProductVariantMutationErrorCode.NOT_FOUND, "No product variant found for the id")));
        }
        ProductVariant row = addressed.get();
        // The open-reservation wall — the product delete's own B7 law on
        // the variant shelf: a variant holding reserved units is the paid
        // order's stock and can never be released or fulfilled again once
        // soft-deleted, so the delete refuses with the classified INVALID
        // until the reservation is released or fulfilled.
        if (row.getReservedQuantity() > 0) {
            return ProductVariantMutationResult.failure(List.of(new ProductVariantMutationError(
                    "id", ProductVariantMutationErrorCode.INVALID,
                    "The product variant holds open reservations and cannot be deleted")));
        }
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
        // The 2026-11-07 #808 round-8 review (Minor · Stability): the row
        // locks come in ONE sorted, deduplicated, null-free pass (two
        // concurrent bulk deletes naming overlapping ids in opposite
        // orders can never deadlock), and the caller's identity
        // is resolved ONCE for the whole batch (the per-row ownership
        // filter reuses it — the ProductWriteService.deleteProducts
        // precedent). The 2026-11-08 #808 round-22 review: the pass sorts
        // in the DATABASE's own UUID order (VARIANT_LOCK_ORDER) — the
        // roster read's ORDER BY is a memcmp order Java's natural
        // sorting does not reproduce, and the round-8/round-21 passes
        // silently used two different orders on the same rows.
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        for (UUID id : ids.stream().filter(java.util.Objects::nonNull).distinct()
                .sorted(VARIANT_LOCK_ORDER).toList()) {
            // Unknown or stranger ids are skipped — the honest affected
            // count answers only what the caller owns and the registry
            // itself deleted (never a fabricated count, never an
            // existence disclosure for another provider's row). The
            // 2026-11-05 #808 round (Major): each candidate rides the SAME
            // locked read the single delete owns, and a row holding open
            // reservations is SKIPPED — not counted, not deleted (the
            // bulk shape of the wall above; the count-plus-errors law
            // stays honest: the blocked row is neither deleted nor
            // counted).
            Optional<ProductVariant> addressed = ownedRowForUpdate(id, caller);
            if (addressed.isPresent() && addressed.get().getReservedQuantity() == 0) {
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
            // The 2026-11-07 #808 round-19 review (Minor · Functional
            // Correctness): a null element answers the per-item REQUIRED
            // error (the bulk-update path's own law) — never a
            // NullPointerException that aborts the whole batch.
            if (command == null) {
                outcomes.add(new ProductVariantBulkOutcome(null, List.of(
                        new ProductVariantMutationError("command",
                                ProductVariantMutationErrorCode.REQUIRED,
                                "A bulk create item requires its variant command"))));
                continue;
            }
            // Self-invocation joins THIS transaction (the bulk boundary
            // owns the authorization and the policy); the single-item
            // ladder's own validation answers per item.
            ProductVariantMutationResult result;
            try {
                result = createVariant(
                        new ProductVariantCreateCommand(productId, command.sku(), command.name(),
                                command.priceMinor()),
                        authentication);
            } catch (org.springframework.dao.DataIntegrityViolationException violation) {
                // The 2026-11-08 #808 round-22 review (Minor · Data
                // Integrity & Integration): the violation can surface
                // INSIDE the loop, BEFORE the guarded flush — an earlier
                // item's pending insert is auto-flushed by a LATER
                // item's SKU precheck (FlushMode.AUTO flushes before
                // any query over product_variants, and
                // countSkuReservationsIncludingDeleted reads it), so a
                // SKU claim committed after the earlier item's precheck
                // aborted the batch here with the raw constraint error.
                // The same discrimination the flush catch owns (this
                // index only; every other violation keeps its own
                // propagation) and the same whole-batch rewrite: the
                // statement failure already aborted the transaction
                // (PostgreSQL's own law — no per-item isolation inside
                // it exists to assume), so the rollback is marked first
                // and EVERY item answers the collision honestly, the
                // ones past the failure included.
                if (violation.getMessage() == null
                        || !violation.getMessage().contains("uq_product_variants_provider_sku")) {
                    throw violation;
                }
                org.springframework.transaction.interceptor.TransactionAspectSupport
                        .currentTransactionStatus().setRollbackOnly();
                ProductVariantMutationError collision = new ProductVariantMutationError(
                        "sku", ProductVariantMutationErrorCode.ALREADY_EXISTS,
                        "The SKU was committed concurrently in this seller's namespace — the batch"
                                + " rolled back whole");
                List<ProductVariantBulkOutcome> rewritten = new ArrayList<>(commands.size());
                for (ProductVariantBulkOutcome outcome : outcomes) {
                    rewritten.add(outcome.success()
                            ? new ProductVariantBulkOutcome(null, List.of(collision))
                            : new ProductVariantBulkOutcome(null,
                                    appendError(outcome.errors(), collision)));
                }
                while (rewritten.size() < commands.size()) {
                    rewritten.add(new ProductVariantBulkOutcome(null, List.of(collision)));
                }
                return rewritten;
            }
            outcomes.add(new ProductVariantBulkOutcome(result.variant(), result.errors()));
        }
        try {
            // The 2026-11-08 #808 round-21 review (Minor · Data Integrity
            // & Integration, additional comment): the flush lands the
            // pending inserts INSIDE the boundary — the SKU precheck is
            // honest but not atomic, and under a non-atomic policy a
            // concurrent SKU claim committed after this item's precheck
            // surfaces its violation at the interceptor's commit (past
            // every catch), aborting the whole batch with the raw
            // constraint error instead of the classified outcome (the
            // PostgreSQL engine law: a transaction aborted on a
            // statement error accepts no per-item isolation inside it —
            // the bulkCreateAttributes precedent verbatim). The flush
            // moves the collision HERE; the discrimination names THIS
            // index only, every other violation keeps its own
            // propagation.
            variantRepository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException violation) {
            if (violation.getMessage() == null
                    || !violation.getMessage().contains("uq_product_variants_provider_sku")) {
                throw violation;
            }
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            ProductVariantMutationError collision = new ProductVariantMutationError(
                    "sku", ProductVariantMutationErrorCode.ALREADY_EXISTS,
                    "The SKU was committed concurrently in this seller's namespace — the batch"
                            + " rolled back whole");
            List<ProductVariantBulkOutcome> rewritten = new ArrayList<>(outcomes.size());
            for (ProductVariantBulkOutcome outcome : outcomes) {
                rewritten.add(outcome.success()
                        ? new ProductVariantBulkOutcome(null, List.of(collision))
                        : new ProductVariantBulkOutcome(null,
                                appendError(outcome.errors(), collision)));
            }
            return rewritten;
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
        // The 2026-11-05 #808 round (Minor · Data Integrity): the unknown
        // policy answers the classified per-item INVALID up front — the
        // bulkCreate root's own check. The previous shape fell through to
        // atomic()'s false and silently saved the partial batch as if
        // IGNORE_FAILED had been sent (the silent non-atomic save the
        // policy contract exists to prevent).
        ProductVariantMutationError policyError = unknownPolicyError(errorPolicy);
        if (policyError != null) {
            for (ProductVariantBulkUpdateItem item : items) {
                outcomes.add(new ProductVariantBulkOutcome(null, List.of(policyError)));
            }
            return outcomes;
        }
        // The 2026-11-08 #808 round-22 review (Minor · Stability &
        // Availability, the bulkUpdateAttributes class on the variant
        // writers): the row locks come in ONE sorted pass BEFORE the
        // per-item loop — the previous shape took each item's
        // ownedRowForUpdate inside the CALLER's list order, so two
        // concurrent bulk updates naming overlapping variants in
        // opposite orders could deadlock (each holding one row and
        // waiting for the other's), and a bulk update racing the
        // product delete's roster read could deadlock the same way (the
        // roster locks in the database's own ORDER BY). The pass locks
        // OWNED rows only (a skipped foreign row never locks — the
        // deleteVariants precedent), in the module's variant lock order
        // (VARIANT_LOCK_ORDER, the database's own UUID byte order); the
        // per-item re-lock inside updateVariant is free (this
        // transaction already holds the rows), and the items still
        // process and answer in the caller's own order.
        UUID bulkCaller = currentUserProvider.getCurrentUserId(authentication);
        for (UUID id : items.stream()
                .filter(item -> item != null && item.id() != null)
                .map(ProductVariantBulkUpdateItem::id)
                .distinct()
                .sorted(VARIANT_LOCK_ORDER)
                .toList()) {
            ownedRowForUpdate(id, bulkCaller);
        }
        for (ProductVariantBulkUpdateItem item : items) {
            // The 2026-11-06 #808 round-3 review (Minor · Stability &amp;
            // Availability): the per-item REQUIRED guards come BEFORE any
            // repository call — a null id reached Spring Data's findById
            // as IllegalArgumentException and a null fields command
            // reached updateVariant's command.isEmpty() as a
            // NullPointerException (the single path's own null-id guard
            // lives in ownedRowForUpdate; the bulk path walks its own
            // ladder and answers the classified REQUIRED entry instead).
            if (item == null || item.id() == null) {
                outcomes.add(new ProductVariantBulkOutcome(null, List.of(
                        new ProductVariantMutationError("id", ProductVariantMutationErrorCode.REQUIRED,
                                "A bulk update item requires the variant's id"))));
                continue;
            }
            if (item.fields() == null) {
                outcomes.add(new ProductVariantBulkOutcome(null, List.of(
                        new ProductVariantMutationError("fields", ProductVariantMutationErrorCode.REQUIRED,
                                "A bulk update item requires its fields command"))));
                continue;
            }
            // The shard review (2026-11-01, Minor · Data Integrity): the
            // Saleor product argument is a BINDING ownership claim — an
            // item whose row belongs to another product answers the
            // per-row NOT_FOUND (the safe absence, the same vocabulary
            // ownedRow uses), never a cross-product amendment.
            //
            // The 2026-11-07 #808 round-19 review (Minor · Data Integrity
            // & Integration, both halves): (1) the probe is a PROJECTION
            // (never the entity) — the previous unlocked findById loaded
            // the row into the persistence context BEFORE the single
            // path's findByIdForUpdate took the row lock, and a locking
            // query on an already-managed entity returns the CACHED
            // pre-lock state (the round-11 drop-id/lock/load discipline,
            // applied to the bulk shape); (2) the refusal rides the
            // SINGLE path's own ('id', NOT_FOUND, "No product variant
            // found for the id") vocabulary — the previous ('product',
            // NOT_FOUND) shape distinguished a foreign-owned variant
            // under a valid product ('id' refusal from ownedRow) from an
            // unknown or differently-parented pair ('product' refusal)
            // — an association/existence oracle the documented
            // safe-absence law forbids.
            if (productId == null
                    || !variantRepository.existsByIdAndProductId(item.id(), productId)) {
                outcomes.add(new ProductVariantBulkOutcome(null, List.of(
                        new ProductVariantMutationError("id",
                                ProductVariantMutationErrorCode.NOT_FOUND,
                                "No product variant found for the id"))));
                continue;
            }
            ProductVariantMutationResult result;
            try {
                result = updateVariant(item.id(), item.fields(), authentication);
            } catch (org.springframework.dao.DataIntegrityViolationException violation) {
                // The 2026-11-08 #808 round-24 review (Minor · Data
                // Integrity & Integration): the bulkCreate loop's own
                // twin — the violation can surface INSIDE the loop,
                // BEFORE any guarded flush — an earlier item's pending
                // UPDATE is auto-flushed by a LATER item's SKU precheck
                // (FlushMode.AUTO flushes before any query over
                // product_variants, and
                // countSkuReservationsIncludingDeletedExcluding reads
                // it), so a SKU claim committed after the earlier item's
                // precheck aborted the batch here with the raw
                // constraint error. The same discrimination the create
                // loop owns (this index only; every other violation
                // keeps its own propagation) and the same whole-batch
                // rewrite: the statement failure already aborted the
                // transaction (PostgreSQL's own law — no per-item
                // isolation inside it exists to assume), so the rollback
                // is marked first and EVERY item answers the collision
                // honestly, the ones past the failure included.
                if (violation.getMessage() == null
                        || !violation.getMessage().contains("uq_product_variants_provider_sku")) {
                    throw violation;
                }
                org.springframework.transaction.interceptor.TransactionAspectSupport
                        .currentTransactionStatus().setRollbackOnly();
                ProductVariantMutationError collision = new ProductVariantMutationError(
                        "sku", ProductVariantMutationErrorCode.ALREADY_EXISTS,
                        "The SKU was committed concurrently in this seller's namespace — the batch"
                                + " rolled back whole");
                List<ProductVariantBulkOutcome> rewritten = new ArrayList<>(items.size());
                for (ProductVariantBulkOutcome outcome : outcomes) {
                    rewritten.add(outcome.success()
                            ? new ProductVariantBulkOutcome(null, List.of(collision))
                            : new ProductVariantBulkOutcome(null,
                                    appendError(outcome.errors(), collision)));
                }
                while (rewritten.size() < items.size()) {
                    rewritten.add(new ProductVariantBulkOutcome(null, List.of(collision)));
                }
                return rewritten;
            }
            outcomes.add(new ProductVariantBulkOutcome(result.variant(), result.errors()));
        }
        try {
            // The 2026-11-08 #808 round-24 review (the bulkCreate root's
            // own law): the flush lands the pending UPDATEs INSIDE the
            // boundary — the SKU precheck is honest but not atomic, and
            // the LAST item's pending update has no later precheck to
            // auto-flush it, so without this flush a concurrent SKU
            // claim committed after that item's precheck surfaces its
            // violation at the interceptor's commit (past every catch),
            // aborting the whole batch with the raw constraint error
            // instead of the classified outcome.
            variantRepository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException violation) {
            if (violation.getMessage() == null
                    || !violation.getMessage().contains("uq_product_variants_provider_sku")) {
                throw violation;
            }
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            ProductVariantMutationError collision = new ProductVariantMutationError(
                    "sku", ProductVariantMutationErrorCode.ALREADY_EXISTS,
                    "The SKU was committed concurrently in this seller's namespace — the batch"
                            + " rolled back whole");
            List<ProductVariantBulkOutcome> rewritten = new ArrayList<>(outcomes.size());
            for (ProductVariantBulkOutcome outcome : outcomes) {
                rewritten.add(outcome.success()
                        ? new ProductVariantBulkOutcome(null, List.of(collision))
                        : new ProductVariantBulkOutcome(null,
                                appendError(outcome.errors(), collision)));
            }
            return rewritten;
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

    /** The collision rewrite's own combiner (the bulkCreateAttributes precedent verbatim). */
    private static List<ProductVariantMutationError> appendError(List<ProductVariantMutationError> errors,
                                                                 ProductVariantMutationError collision) {
        List<ProductVariantMutationError> combined = new ArrayList<>(errors);
        combined.add(collision);
        return List.copyOf(combined);
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
     * The WRITE paths' own ownership read — the safe-absence law
     * (a missing row and a stranger's row answer the same empty, the R5
     * shape), through the pessimistic locked read
     * ({@code findByIdForUpdate}): the variant reservation is a native
     * UPDATE on the row alone, so the delete's reservation wall and its
     * soft-delete both ride the row lock a concurrent placement
     * serializes on (the 2026-11-05 #808 round — the product delete's
     * own {@code ownedRowForUpdate} twin).
     */
    private Optional<ProductVariant> ownedRowForUpdate(UUID id, Authentication authentication) {
        return ownedRowForUpdate(id, currentUserProvider.getCurrentUserId(authentication));
    }

    /** The batch's own shape — the caller's identity resolved once, reused per row. */
    private Optional<ProductVariant> ownedRowForUpdate(UUID id, UUID caller) {
        if (id == null || caller == null) {
            return Optional.empty();
        }
        // The 2026-11-08 #808 round-21 review (Minor · Stability &
        // Availability, additional comment): the provider predicate rides
        // INSIDE the locking query (the ProductRepository
        // .findByIdAndProviderIdForUpdate precedent verbatim) — a skipped
        // foreign row never locks.
        return variantRepository.findByIdAndProviderIdForUpdate(id, caller);
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
