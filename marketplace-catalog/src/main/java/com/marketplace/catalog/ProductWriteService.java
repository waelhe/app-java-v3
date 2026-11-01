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
    private final CurrentUserProvider currentUserProvider;

    public ProductWriteService(ProductRepository productRepository,
                               StoreCategoryRepository storeCategoryRepository,
                               ProductTypeRepository productTypeRepository,
                               CurrentUserProvider currentUserProvider) {
        this.productRepository = productRepository;
        this.storeCategoryRepository = storeCategoryRepository;
        this.productTypeRepository = productTypeRepository;
        this.currentUserProvider = currentUserProvider;
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
        if (!currency.matches("[A-Z]{3}")) {
            errors.add(new ProductMutationError("currency", ProductMutationErrorCode.REQUIRED,
                    "A product is denominated in a 3-letter ISO-4217 currency"));
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
                && productTypeRepository.findById(command.productTypeId()).isEmpty()) {
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
        Optional<Product> addressed = ownedRow(id, authentication);
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
        Optional<Product> addressed = ownedRow(id, authentication);
        if (addressed.isEmpty()) {
            return ProductMutationResult.failure(List.of(new ProductMutationError(
                    "id", ProductMutationErrorCode.NOT_FOUND, "No product found for the id")));
        }
        Product row = addressed.get();
        ProductView lastKnown = toView(row);
        productRepository.delete(row);
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
        for (UUID id : ids) {
            // Unknown or stranger ids are skipped — the honest affected
            // count answers only what the caller owns and the registry
            // itself deleted (never a fabricated count, never an
            // existence disclosure for another provider's row).
            Optional<Product> addressed = ownedRow(id, authentication);
            if (addressed.isPresent()) {
                productRepository.delete(addressed.get());
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public List<ProductBulkOutcome> bulkCreateProducts(List<ProductCreateCommand> commands,
                                                       Authentication authentication, String errorPolicy) {
        List<ProductBulkOutcome> outcomes = new ArrayList<>(commands.size());
        for (ProductCreateCommand command : commands) {
            // Self-invocation joins THIS transaction (the bulk boundary
            // owns the authorization and the policy); the single-item
            // ladder's own validation answers per item with the caller
            // as every created row's owning provider.
            ProductMutationResult result = createProduct(command, authentication);
            outcomes.add(new ProductBulkOutcome(result.product(), result.errors()));
        }
        if ((errorPolicy == null || "REJECT_EVERYTHING".equals(errorPolicy))
                && outcomes.stream().anyMatch(outcome -> !outcome.success())) {
            // The pinned default policy: one refused item refuses the
            // whole batch — the official rollback-marking mechanism.
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
        }
        return outcomes;
    }

    /**
     * The caller's own row — the ownership law's own safe absence: a
     * missing row and a stranger's row answer the same empty (the R5
     * shape, never an existence disclosure).
     */
    private Optional<Product> ownedRow(UUID id, Authentication authentication) {
        if (id == null) {
            return Optional.empty();
        }
        return productRepository.findById(id)
                .filter(row -> row.getProviderId().equals(currentUserProvider.getCurrentUserId(authentication)));
    }

    /** The bounded projection — the read service's own rendering rule. */
    private static ProductView toView(Product product) {
        return new ProductView(product.getId(), product.getTitle(),
                product.getProductTypeId(), product.getStatus().name());
    }
}
