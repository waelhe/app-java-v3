package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductBulkOutcome;
import com.marketplace.catalog.spi.ProductCreateCommand;
import com.marketplace.catalog.spi.ProductMutationError;
import com.marketplace.catalog.spi.ProductMutationErrorCode;
import com.marketplace.catalog.spi.ProductMutationResult;
import com.marketplace.catalog.spi.ProductUpdateCommand;
import com.marketplace.catalog.spi.ProductView;
import com.marketplace.catalog.spi.ProductWriteSpi;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productCreate}, {@code productUpdate}, {@code productDelete}
 * and {@code productBulkDelete} mutation roots, the agent-2 packet):
 * the owning use case for the store's product write surface. These are
 * Saleor MANAGEMENT roots ("Requires one of the following permissions:
 * MANAGE_PRODUCTS") executed over the platform's own ownership law for
 * provider-owned rows: the owning provider IS the caller — the row's
 * {@code providerId} resolves from the authentication through the
 * {@code CurrentUserProvider} seam, and a stranger's row answers the
 * safe absence (the R5 shape {@code ProductService.getOwnedProduct}
 * itself established). Authorization is enforced HERE at the owning
 * use-case boundary ({@code @PreAuthorize("hasRole('PROVIDER')")} —
 * the same mechanism the module's own REST write paths use), never
 * only in a resolver.
 *
 * <p><b>The validation rules are the row's own contracts</b> — the
 * entity factory's own documented shapes: the title is 1..200
 * characters, the description at most 2000, the price non-negative
 * minor units, the currency a 3-letter ISO-4217 code, the
 * store-category code a dictionary member, the optional goods schema
 * a registry member. The service re-checks all of them BEFORE the
 * write; domain outcomes answer as result error entries (the Saleor
 * payload-error ADAPT); transport concerns never reach this
 * service.</p>
 *
 * <p><b>Update is partial</b> — {@code null} command fields preserve
 * the column, the Saleor input's omit-to-preserve semantics — and the
 * movable set is exactly the platform's own product-update law (title,
 * description, price); an ARCHIVED row is terminal and answers the
 * INVALID domain error (the entity's own documented law, classified
 * here instead of thrown). <b>Soft delete</b> rides the
 * {@code BaseEntity} {@code @SoftDelete} discipline:
 * {@code repository.delete} converts to the {@code is_deleted} flag,
 * and the deleted row's last-known projection answers in the payload
 * (Saleor's own delete-payload semantics).</p>
 */
@Service
public class ProductWriteService implements ProductWriteSpi {

    /** The row's own column ceilings (the entity's own length contracts). */
    private static final int TITLE_MAX_LENGTH = 200;
    private static final int DESCRIPTION_MAX_LENGTH = 2000;

    private final ProductRepository productRepository;
    private final StoreCategoryRepository storeCategoryRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ProductVariantRepository variantRepository;
    private final CurrentUserProvider currentUserProvider;
    /** The product delete's membership cascade (the 2026-11-07 #808 round-19). */
    private final CollectionProductRepository collectionProducts;

    public ProductWriteService(ProductRepository productRepository,
                               StoreCategoryRepository storeCategoryRepository,
                               ProductTypeRepository productTypeRepository,
                               ProductVariantRepository variantRepository,
                               CurrentUserProvider currentUserProvider,
                               CollectionProductRepository collectionProducts) {
        this.productRepository = productRepository;
        this.storeCategoryRepository = storeCategoryRepository;
        this.productTypeRepository = productTypeRepository;
        this.variantRepository = variantRepository;
        this.currentUserProvider = currentUserProvider;
        this.collectionProducts = collectionProducts;
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public ProductMutationResult createProduct(ProductCreateCommand command, Authentication authentication) {
        List<ProductMutationError> errors = new ArrayList<>();
        String title = command.name() == null ? null : command.name().strip();
        if (title == null || title.isBlank()) {
            errors.add(new ProductMutationError("name", ProductMutationErrorCode.REQUIRED,
                    "A product name is required"));
        } else if (title.length() > TITLE_MAX_LENGTH) {
            errors.add(new ProductMutationError("name", ProductMutationErrorCode.INVALID,
                    "A product name is at most " + TITLE_MAX_LENGTH + " characters"));
        }
        String description = command.description() == null ? null : command.description().strip();
        if (description != null && description.length() > DESCRIPTION_MAX_LENGTH) {
            errors.add(new ProductMutationError("description", ProductMutationErrorCode.INVALID,
                    "A product description is at most " + DESCRIPTION_MAX_LENGTH + " characters"));
        }
        if (command.priceMinor() == null) {
            errors.add(new ProductMutationError("priceMinor", ProductMutationErrorCode.REQUIRED,
                    "A product requires a price"));
        } else if (command.priceMinor() < 0) {
            errors.add(new ProductMutationError("priceMinor", ProductMutationErrorCode.INVALID,
                    "A product's price is a non-negative minor amount"));
        }
        String currency = command.currency() == null ? "" : command.currency().strip().toUpperCase(Locale.ROOT);
        // The 2026-11-05 #809 round (Minor · Functional Correctness): the
        // absent/malformed split the vocabulary itself defines — REQUIRED
        // is the absent-field family, INVALID the malformed-shape family.
        // The previous single branch classified a SUPPLIED "riyals" as
        // REQUIRED against the vocabulary's own description.
        if (currency.isEmpty()) {
            errors.add(new ProductMutationError("currency", ProductMutationErrorCode.REQUIRED,
                    "A product is denominated in a 3-letter ISO-4217 currency"));
        } else if (!currency.matches("[A-Z]{3}")) {
            errors.add(new ProductMutationError("currency", ProductMutationErrorCode.INVALID,
                    "A product's currency is a 3-letter ISO-4217 code"));
        }
        String storeCategoryCode = command.storeCategoryCode() == null
                ? null
                : command.storeCategoryCode().strip();
        if (storeCategoryCode == null || storeCategoryCode.isBlank()) {
            errors.add(new ProductMutationError("storeCategoryCode", ProductMutationErrorCode.REQUIRED,
                    "A product requires its store-category code"));
        } else if (storeCategoryRepository.findByCode(storeCategoryCode).isEmpty()) {
            errors.add(new ProductMutationError("storeCategoryCode", ProductMutationErrorCode.NOT_FOUND,
                    "No store category found for the code"));
        }
        if (command.productTypeId() != null
                // B21 (CAT-09 — the 2026-11-03 adversarial review, review-pr-808):
                // the locking read (FOR SHARE, held for THIS transaction's
                // lifetime) — a concurrent productTypeDelete's soft-delete
                // UPDATE blocks until the create commits, so the type the
                // create validated is the type the product references.
                && productTypeRepository.findByIdForShare(command.productTypeId()).isEmpty()) {
            errors.add(new ProductMutationError("productType", ProductMutationErrorCode.NOT_FOUND,
                    "No product type found for the id"));
        }
        if (!errors.isEmpty()) {
            return ProductMutationResult.failure(errors);
        }
        UUID providerId = currentUserProvider.getCurrentUserId(authentication);
        Product created = productRepository.save(Product.register(
                storeCategoryCode, title, description, command.priceMinor(), currency,
                providerId, command.productTypeId()));
        return ProductMutationResult.success(toView(created));
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public ProductMutationResult updateProduct(UUID id, ProductUpdateCommand command, Authentication authentication) {
        // The 2026-11-05 #808 round (Major · Data Integrity): the update
        // rides the LOCKED ownership read — the plain findById raced a
        // concurrent delete's reservation wall and a lost update
        // (reviseDetails + save over a row another transaction moved):
        // the same ownedRowForUpdate the delete paths ride.
        Optional<Product> addressed = ownedRowForUpdate(id, authentication);
        if (addressed.isEmpty()) {
            return ProductMutationResult.failure(List.of(new ProductMutationError(
                    "id", ProductMutationErrorCode.NOT_FOUND, "No product found for the id")));
        }
        if (command.isEmpty()) {
            return ProductMutationResult.success(toView(addressed.get()));
        }
        Product row = addressed.get();
        if (row.getStatus() == StoreProductStatus.ARCHIVED) {
            // The entity's own terminal law, classified as the payload's
            // own INVALID outcome — never an IllegalStateException.
            return ProductMutationResult.failure(List.of(new ProductMutationError(
                    null, ProductMutationErrorCode.INVALID,
                    "Archived products are terminal and cannot be edited")));
        }
        List<ProductMutationError> errors = new ArrayList<>();
        String mergedTitle = command.name() == null ? row.getTitle() : command.name().strip();
        if (mergedTitle == null || mergedTitle.isBlank()) {
            errors.add(new ProductMutationError("name", command.name() == null
                    ? ProductMutationErrorCode.REQUIRED : ProductMutationErrorCode.INVALID,
                    "A product name is required"));
        } else if (mergedTitle.length() > TITLE_MAX_LENGTH) {
            errors.add(new ProductMutationError("name", ProductMutationErrorCode.INVALID,
                    "A product name is at most " + TITLE_MAX_LENGTH + " characters"));
        }
        String mergedDescription = command.description() == null
                ? row.getDescription()
                : command.description().strip();
        if (mergedDescription != null && mergedDescription.length() > DESCRIPTION_MAX_LENGTH) {
            errors.add(new ProductMutationError("description", ProductMutationErrorCode.INVALID,
                    "A product description is at most " + DESCRIPTION_MAX_LENGTH + " characters"));
        }
        long mergedPriceMinor = command.priceMinor() == null ? row.getPriceMinor() : command.priceMinor();
        if (mergedPriceMinor < 0) {
            errors.add(new ProductMutationError("priceMinor", ProductMutationErrorCode.INVALID,
                    "A product's price is a non-negative minor amount"));
        }
        if (!errors.isEmpty()) {
            return ProductMutationResult.failure(errors);
        }
        row.updateDetails(mergedTitle, mergedDescription, mergedPriceMinor);
        return ProductMutationResult.success(toView(productRepository.save(row)));
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public ProductMutationResult deleteProduct(UUID id, Authentication authentication) {
        // B7's TOCTOU residual (CAT-04 — the 2026-11-04 follow-on): the
        // row is loaded THROUGH the pessimistic locked read, never the
        // unlocked findById — the ownership safe-absence law is unchanged
        // (a missing row and a stranger's row answer the same empty), but
        // the wall below now checks the LOCKED counters.
        Optional<Product> addressed = ownedRowForUpdate(id, authentication);
        if (addressed.isEmpty()) {
            return ProductMutationResult.failure(List.of(new ProductMutationError(
                    "id", ProductMutationErrorCode.NOT_FOUND, "No product found for the id")));
        }
        Product row = addressed.get();
        // B7 (CAT-04 — the 2026-11-03 adversarial review, review-pr-808):
        // the open-reservation wall. The delete is a SOFT delete, and the
        // reservation machinery (reserve/release/commit) rides bulk JPQL
        // that Hibernate's @SoftDelete filters — once the row is
        // soft-deleted, a release or commit on it matches ZERO rows and
        // the orders owner's ConflictException fires: a paid order's
        // stock could never be fulfilled or released again (the U1
        // probe's own measured evidence, pinned in
        // ProductGraphQlIntegrationTest's B7 legs). The honest
        // contract: a product holding open reservations — its own counter
        // or any of its variants' (the delete cascades them) — is
        // refused with the classified INVALID error until the reserved
        // units are released or fulfilled.
        //
        // The lock design (why these two locks close the race): the wall's
        // first shape was check-then-act — an unlocked read, while the
        // placement's reservation is a bulk UPDATE that does NOT touch
        // the @Version column, so a delete that passed the guard on
        // reserved=0 could soft-delete the row WHILE the reservation
        // committed, stranding the paid order's stock in the unreleasable
        // U1 state. Now BOTH shelves are loaded under PESSIMISTIC_WRITE
        // (the money path's own VoucherRepository.findByCodeForUpdate
        // pattern): the product row through findByIdForUpdate —
        // ProductRepository.reserve's bulk UPDATE writes that row, so the
        // lock serializes against it (either the delete holds the row and
        // the soft-delete-filtered reservation matches ZERO rows → the
        // placement's own honest 409 rolls back whole, or the reservation
        // committed first and the locked read sees the counter and
        // refuses) — and the variant roster through
        // findByProductIdOrderBySkuAscForUpdate, because the VARIANT
        // reservation is a native UPDATE on the variant row ALONE (its
        // parent gate is a non-locking EXISTS, and a variant cart line
        // never increments the parent product's counter — ADR-0012's
        // placement splits the shelves), so the product-row lock alone
        // cannot close the variant race: the variant rows carry their
        // own. The delete itself still rides the ENTITY
        // (repository.delete → Hibernate's @SoftDelete UPDATE), so the
        // Envers audit revision — AGENTS.md's "all domain entities MUST
        // have @Audited" discipline — is unchanged.
        if (row.getReservedQuantity() > 0 || variantsHoldOpenReservations(row.getId())) {
            return ProductMutationResult.failure(List.of(new ProductMutationError(
                    "id", ProductMutationErrorCode.INVALID,
                    "A product holding open reservations cannot be deleted — release or "
                            + "fulfill the reserved units first (reserved: "
                            + row.getReservedQuantity() + ")")));
        }
        ProductView lastKnown = toView(row);
        productRepository.delete(row);
        // The wave-19 #808 review's preferred law (Major · Stability): the
        // variants' shelf IS the product's — a deleted product's variants
        // cascade to the same soft delete (they are unreachable otherwise:
        // the read side answers the safe absence while the write side
        // still owned them). Inside the same transaction, all-or-nothing.
        deleteVariantsOf(row.getId());
        // The 2026-11-07 #808 round-19 review (Minor · Data Integrity &
        // Integration): the collection memberships are the product's own
        // shelf too — the variants' cascade twin. Without the cascade a
        // soft-deleted product left LIVE membership rows the read join
        // hid (the member never rendered) while the reorder still walked
        // them and the membership count kept them — the invisible member
        // the round-19 finding proved. The membership add's FOR-SHARE
        // read (CollectionWriteService.appendMembership) serializes
        // against THIS delete's row lock, so the pair never strands a
        // new membership behind the cascade either.
        deleteMembershipsOf(row.getId());
        return ProductMutationResult.success(lastKnown);
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public int deleteProducts(List<UUID> ids, Authentication authentication) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        // The 2026-11-06 #808 round-3 review (Minor · Stability &amp;
        // Availability): the caller's identity is resolved ONCE for the
        // whole batch — the per-row ownership filter is the same value
        // every candidate reuses (getCurrentUserId hits the user source
        // per call; a 500-row delete paid it 500 times).
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        for (UUID id : ids) {
            // Unknown or stranger ids are skipped — the honest affected
            // count answers only what the caller owns and the registry
            // itself deleted (never a fabricated count, never an
            // existence disclosure for another provider's row).
            // B7's TOCTOU residual (CAT-04): each candidate rides the SAME
            // locked read the single delete owns — the per-row guard below
            // checks the LOCKED counters, so a reservation that lands
            // between any two candidates is seen, not raced. The locks are
            // taken in the caller's list order; two concurrent bulk
            // deletes naming the same rows in opposite orders are untangled
            // by the database's own deadlock detector (one whole delete
            // rolls back — never a stranded reservation, which is the
            // state this wall exists to prevent).
            Optional<Product> addressed = ownedRowForUpdate(id, caller);
            if (addressed.isPresent()) {
                // B7 (CAT-04): the single delete's open-reservation wall,
                // the bulk shape — a blocked row is skipped and NOT
                // counted (the count answers only what the registry
                // itself deleted; the single root carries the classified
                // refusal for the same state).
                if (addressed.get().getReservedQuantity() > 0
                        || variantsHoldOpenReservations(id)) {
                    continue;
                }
                productRepository.delete(addressed.get());
                // The single delete's cascade (the same wave-19 law).
                deleteVariantsOf(id);
                // The single delete's membership cascade (the round-19 law).
                deleteMembershipsOf(id);
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * B7 (CAT-04): whether any of the product's variants still holds a
     * reservation — the delete cascades the variant shelf, so an open
     * variant reservation is the same unreleasable state the product's
     * own counter carries (ADR-0012's reservation spans BOTH shelves).
     * The roster is read through the LOCKED variant roster
     * (findByProductIdOrderBySkuAscForUpdate): the variant reservation is
     * a native UPDATE on the variant row alone, so the variant rows —
     * not just the parent product row — must be under the delete's
     * pessimistic lock for the guard to be race-free (the locks are held
     * until the delete transaction commits, so nothing can land between
     * this check and the cascade below).
     */
    private boolean variantsHoldOpenReservations(UUID productId) {
        for (ProductVariant variant : variantRepository.findByProductIdOrderBySkuAscForUpdate(productId)) {
            if (variant.getReservedQuantity() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The product's live variant rows — soft-deleted WITH the product
     * (the wave-19 #808 review's cascade: a deleted product never leaves
     * an owned orphan shelf behind). Read through the same LOCKED roster
     * the guard used: within one transaction the second
     * {@code SELECT ... FOR UPDATE} acquires nothing new (this transaction
     * already holds the row locks), and the delete's own UPDATE of the
     * variant row is therefore serialized against any concurrent variant
     * reservation exactly like the guard's read.
     */
    private void deleteVariantsOf(UUID productId) {
        for (ProductVariant variant : variantRepository.findByProductIdOrderBySkuAscForUpdate(productId)) {
            variantRepository.delete(variant);
        }
    }

    /**
     * The 2026-11-07 #808 round-19 review: the membership cascade — the
     * variants' cascade twin (the membership's shelf IS the product's).
     *
     * <p>The 2026-11-07 #808 round-20 review (Minor · Stability &amp;
     * Availability): the cascade takes EACH affected collection's own
     * advisory position lock (in sorted collection order — the deadlock
     * law the bulk writers own) BEFORE loading the memberships — a
     * concurrent reorder's park write on the same member row would
     * otherwise answer the raw OptimisticLockException and abort THIS
     * product delete; the lock serializes the pair (the reorder/append
     * writers take the same lock per collection).</p>
     */
    private void deleteMembershipsOf(UUID productId) {
        java.util.List<UUID> collections = collectionProducts
                .findDistinctCollectionIdsByProductId(productId).stream().sorted().toList();
        for (UUID collectionId : collections) {
            collectionProducts.lockCollectionPositionAllocation(collectionId.toString());
        }
        for (CollectionProduct member : collectionProducts.findByProductIdOrderByPositionAsc(productId)) {
            collectionProducts.delete(member);
        }
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public List<ProductBulkOutcome> bulkCreateProducts(List<ProductCreateCommand> commands,
                                                       Authentication authentication, String errorPolicy) {
        List<ProductBulkOutcome> outcomes = new ArrayList<>(commands.size());
        // The wave-19 #808 review (Minor): an unknown policy name is the
        // classified INVALID refusal for every item — never the silent
        // non-atomic persistence path.
        ProductMutationError policyError = unknownPolicyError(errorPolicy);
        if (policyError != null) {
            for (ProductCreateCommand command : commands) {
                outcomes.add(new ProductBulkOutcome(null, List.of(policyError)));
            }
            return outcomes;
        }
        for (ProductCreateCommand command : commands) {
            // The 2026-11-07 #808 round-19 review (Minor · Functional
            // Correctness): a null element answers the per-item REQUIRED
            // error (the bulk-update path's own law) — never a
            // NullPointerException that aborts the whole batch.
            if (command == null) {
                outcomes.add(new ProductBulkOutcome(null, List.of(new ProductMutationError(
                        "command", ProductMutationErrorCode.REQUIRED,
                        "A bulk create item requires its product command"))));
                continue;
            }
            // Self-invocation joins THIS transaction (the bulk boundary
            // owns the authorization and the policy); the single-item
            // ladder's own validation answers per item with the caller
            // as every created row's owning provider.
            ProductMutationResult result = createProduct(command, authentication);
            outcomes.add(new ProductBulkOutcome(result.product(), result.errors()));
        }
        if (atomic(errorPolicy) && outcomes.stream().anyMatch(outcome -> !outcome.success())) {
            // The pinned default policy: one refused item refuses the
            // whole batch — the official rollback-marking mechanism.
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            // The wave-19 #808 review (Major): the rollback erased every
            // row the passing items claim — the outcomes are rebuilt with
            // no view (the Saleor REJECT_EVERYTHING contract: no item
            // returns a saved entity when the batch is refused).
            outcomes = rewriteRolledBackOutcomes(outcomes);
        }
        return outcomes;
    }

    /** The pinned default policy's own verdict — REJECT_EVERYTHING unless another member is sent. */
    private static boolean atomic(String errorPolicy) {
        return errorPolicy == null || "REJECT_EVERYTHING".equals(errorPolicy);
    }

    /** The unknown-policy refusal — the classified INVALID entry, null for a known member. */
    private static ProductMutationError unknownPolicyError(String errorPolicy) {
        if (errorPolicy == null || "REJECT_EVERYTHING".equals(errorPolicy)
                || "IGNORE_FAILED".equals(errorPolicy)
                || "REJECT_FAILED_ROWS".equals(errorPolicy)) {
            return null;
        }
        return new ProductMutationError("errorPolicy", ProductMutationErrorCode.INVALID,
                "The error policy is REJECT_EVERYTHING, IGNORE_FAILED or REJECT_FAILED_ROWS");
    }

    /** The rollback's outcome rewrite — no phantom projection survives the batch's own refusal. */
    private static List<ProductBulkOutcome> rewriteRolledBackOutcomes(List<ProductBulkOutcome> outcomes) {
        List<ProductBulkOutcome> rewritten = new ArrayList<>(outcomes.size());
        for (ProductBulkOutcome outcome : outcomes) {
            if (outcome.success()) {
                rewritten.add(new ProductBulkOutcome(null, List.of(
                        new ProductMutationError("errorPolicy", ProductMutationErrorCode.INVALID,
                                "The batch was rolled back by the REJECT_EVERYTHING policy; this item was not persisted"))));
            } else {
                rewritten.add(outcome);
            }
        }
        return rewritten;
    }

    /**
     * The WRITE paths' own ownership read — the safe-absence law
     * (a missing row and a stranger's row answer the same empty, the R5
     * shape), through the pessimistic locked read
     * ({@code findByIdForUpdate}, the money path's settle-time pattern):
     * the delete's reservation wall and its soft-delete both ride the row
     * lock the concurrent placement's reservation UPDATE serializes on
     * (B7's TOCTOU residual, CAT-04 — the unlocked check-then-act guard
     * could pass on reserved=0 while a reservation committed under it).
     */
    private Optional<Product> ownedRowForUpdate(UUID id, Authentication authentication) {
        return ownedRowForUpdate(id, currentUserProvider.getCurrentUserId(authentication));
    }

    /** The batch's own shape — the caller's identity resolved once, reused per row. */
    private Optional<Product> ownedRowForUpdate(UUID id, UUID caller) {
        if (id == null) {
            return Optional.empty();
        }
        return productRepository.findByIdForUpdate(id)
                .filter(row -> row.getProviderId().equals(caller));
    }

    /** The bounded projection — the read service's own rendering rule. */
    private static ProductView toView(Product product) {
        return new ProductView(product.getId(), product.getTitle(),
                product.getProductTypeId(), product.getStatus().name());
    }
}
