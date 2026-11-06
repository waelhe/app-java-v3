package com.marketplace.catalog;

import com.marketplace.catalog.spi.CategoryCreateCommand;
import com.marketplace.catalog.spi.CategoryMutationError;
import com.marketplace.catalog.spi.CategoryMutationErrorCode;
import com.marketplace.catalog.spi.CategoryMutationResult;
import com.marketplace.catalog.spi.CategoryUpdateCommand;
import com.marketplace.catalog.spi.CategoryView;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code categoryCreate},
 * {@code categoryUpdate}, {@code categoryDelete} and
 * {@code categoryBulkDelete} mutation roots): the owning use case for the
 * listing-category registry's ADMIN write surface. These are Saleor
 * MANAGEMENT roots ("Requires one of the following permissions:
 * MANAGE_PRODUCTS") mapped through the platform's ADMIN capability (the
 * SaleorPermissionCatalog contract) and enforced HERE at the owning
 * use-case boundary by Spring Security's method security — the same
 * {@code @PreAuthorize} mechanism the pricing module's discount reads
 * established — never only in a resolver.
 *
 * <p><b>The validation rules are the registry's own contracts</b> — the
 * entity's own documented shapes: the code is the stable API-facing key
 * whose DB CHECK pins lowercase latin/digits/dashes (the {@code V70}
 * shape), and the GLOBAL {@code uq_categories_code} unique index keeps
 * every code reserved — soft-deleted rows included (the V70 identity
 * stance: referential identity values are never recycled, unlike the
 * V47 slug's presentation identity). The service re-checks both BEFORE
 * the write (the honest precheck over the RAW-table count, which sees
 * the soft-deleted reservations the derived {@code findByCode} lookup
 * cannot) and the global unique index remains the concurrent-write
 * backstop whose loser answers the SAME classified ALREADY_EXISTS —
 * the same no-TOCTOU stance the repository's own javadoc
 * documents. Domain validation outcomes answer as result error entries
 * (the Saleor payload-error ADAPT); transport concerns (malformed typed
 * global IDs, denied authorization) never reach this service.</p>
 *
 * <p><b>Create assigns the display position</b> — the registry's own
 * {@code position} column is platform extension data the Saleor input
 * does not carry: the row lands after the current tail (the honest
 * deterministic choice; a reorder capability, if ever owned, is its own
 * root). <b>Update is partial</b> — {@code null} command fields preserve
 * the column, the Saleor input's omit-to-preserve semantics.
 * <b>Soft delete</b> rides the {@code BaseEntity} {@code @SoftDelete}
 * discipline: {@code repository.delete} converts to the
 * {@code is_deleted} flag, and the deleted row's last-known projection
 * answers in the payload (Saleor's own delete-payload semantics).</p>
 */
@Service
public class CategoryWriteService implements CategoryWriteSpi {

    /**
     * The code shape the DB CHECK itself pins — lowercase latin letters,
     * digits and dashes (the {@code V70} CHECK, mirrored at the boundary
     * so an invalid shape answers the classified INVALID domain error,
     * never a DB CHECK failure.
     */
    static final Pattern CODE_SHAPE = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** The registry's own column ceilings (the entity's own length contracts). */
    private static final int CODE_MAX_LENGTH = 50;
    private static final int NAME_MAX_LENGTH = 100;

    /**
     * The registry's code-identity constraint — V70's GLOBAL unique index
     * name, the concurrent-create backstop the classified catch binds to
     * (the AdBillingJob discrimination precedent: a DIVE carries more
     * than uniqueness, so only the violation that names THIS constraint
     * answers the payload error).
     */
    private static final String CODE_UNIQUENESS_CONSTRAINT = "uq_categories_code";

    private final CategoryRepository categoryRepository;
    /** The insert's own transaction door (the 2026-11-06 #808 round — the AdBillingBatchExecutor precedent). */
    private final CategoryCreationExecutor creationExecutor;

    public CategoryWriteService(CategoryRepository categoryRepository,
                                 CategoryCreationExecutor creationExecutor) {
        this.categoryRepository = categoryRepository;
        this.creationExecutor = creationExecutor;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CategoryMutationResult createCategory(CategoryCreateCommand command) {
        List<CategoryMutationError> errors = new ArrayList<>();
        String name = command.name() == null ? null : command.name().strip();
        String code = command.slug() == null ? null : command.slug().strip().toLowerCase(Locale.ROOT);
        if (name == null || name.isBlank()) {
            errors.add(new CategoryMutationError("name", CategoryMutationErrorCode.REQUIRED,
                    "A category name is required"));
        } else if (name.length() > NAME_MAX_LENGTH) {
            errors.add(new CategoryMutationError("name", CategoryMutationErrorCode.INVALID,
                    "A category name is at most " + NAME_MAX_LENGTH + " characters"));
        }
        if (code == null || code.isBlank()) {
            errors.add(new CategoryMutationError("slug", CategoryMutationErrorCode.REQUIRED,
                    "A category slug is required"));
        } else if (!CODE_SHAPE.matcher(code).matches() || code.length() > CODE_MAX_LENGTH) {
            errors.add(new CategoryMutationError("slug", CategoryMutationErrorCode.INVALID,
                    "A category slug is lowercase latin letters, digits and dashes (at most "
                            + CODE_MAX_LENGTH + " characters)"));
        } else if (categoryRepository.countByCodeIncludingDeleted(code) > 0) {
            // The 2026-11-05 #808 round (Major · Functional Correctness):
            // the reserved-code check spans the RAW table — a soft-deleted
            // category's code is still reserved by V70's global unique
            // index, so the classified ALREADY_EXISTS answers BEFORE the
            // save (never a raw DataIntegrityViolation from the index).
            errors.add(new CategoryMutationError("slug", CategoryMutationErrorCode.ALREADY_EXISTS,
                    "A category with the slug already exists"));
        }
        if (!errors.isEmpty()) {
            return CategoryMutationResult.failure(errors);
        }
        Category created;
        try {
            // The 2026-11-06 #808 round review (Minor · Data Integrity &
            // Integration): the INSERT moved to the executor's OWN
            // transaction — the violation marks the flushing transaction
            // rollback-only, and a catch inside it returns into a commit
            // that throws UnexpectedRollbackException instead of the
            // classified result (the finding's exact 500). Crossing the
            // executor's boundary the loser arrives fully rolled back,
            // and the classification below answers it honestly (the
            // AdBillingBatchExecutor precedent verbatim).
            created = creationExecutor.insert(code, name);
        } catch (DataIntegrityViolationException violation) {
            // The 2026-11-06 #808 round (Minor · Stability &
            // Availability): the reserved-code precheck is honest but
            // not atomic — two concurrent creates of the same code both
            // pass it and V70's global unique index is the backstop. The
            // loser's raw violation answers the SAME classified
            // ALREADY_EXISTS, bound to uq_categories_code ONLY — every
            // other integrity violation keeps its own propagation (never
            // a blanket catch that would hide real registry bugs).
            if (isCategoryCodeConflict(violation)) {
                return CategoryMutationResult.failure(List.of(new CategoryMutationError(
                        "slug", CategoryMutationErrorCode.ALREADY_EXISTS,
                        "A category with the slug already exists")));
            }
            throw violation;
        }
        return CategoryMutationResult.success(toView(created));
    }

    /**
     * The DIVE discrimination: the violation's most specific cause names
     * the code-identity constraint — the same cause-chain walk the
     * AdBillingJob window-uniqueness discrimination uses.
     */
    private static boolean isCategoryCodeConflict(DataIntegrityViolationException violation) {
        Throwable cause = violation;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && message.contains(CODE_UNIQUENESS_CONSTRAINT)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CategoryMutationResult updateCategory(UUID id, CategoryUpdateCommand command) {
        Optional<Category> addressed = id == null ? Optional.empty() : categoryRepository.findById(id);
        if (addressed.isEmpty()) {
            return CategoryMutationResult.failure(List.of(new CategoryMutationError(
                    "id", CategoryMutationErrorCode.NOT_FOUND, "No category found for the id")));
        }
        List<CategoryMutationError> errors = new ArrayList<>();
        String name = command.name() == null ? null : command.name().strip();
        String code = command.slug() == null ? null : command.slug().strip().toLowerCase(Locale.ROOT);
        if (name != null) {
            if (name.isBlank()) {
                errors.add(new CategoryMutationError("name", CategoryMutationErrorCode.INVALID,
                        "A category name cannot be blank"));
            } else if (name.length() > NAME_MAX_LENGTH) {
                errors.add(new CategoryMutationError("name", CategoryMutationErrorCode.INVALID,
                        "A category name is at most " + NAME_MAX_LENGTH + " characters"));
            }
        }
        if (code != null) {
            if (!CODE_SHAPE.matcher(code).matches() || code.length() > CODE_MAX_LENGTH) {
                errors.add(new CategoryMutationError("slug", CategoryMutationErrorCode.INVALID,
                        "A category slug is lowercase latin letters, digits and dashes (at most "
                                + CODE_MAX_LENGTH + " characters)"));
            }
        }
        if (!errors.isEmpty()) {
            return CategoryMutationResult.failure(errors);
        }
        Category row = addressed.get();
        // The wave-19 #808 review (Major · Functional Correctness): the
        // code is the category's stable reference identity — the entity's
        // own contract pins it immutable. A slug command equal to the
        // row's own code is the idempotent re-send (it preserves the
        // column); any DIFFERENT slug answers the classified INVALID
        // domain error — never a silent code move that strands the
        // standing references.
        if (code != null && !code.equals(row.getCode())) {
            errors.add(new CategoryMutationError("slug", CategoryMutationErrorCode.INVALID,
                    "A category slug is immutable; it cannot change after creation"));
        }
        if (!errors.isEmpty()) {
            return CategoryMutationResult.failure(errors);
        }
        row.revise(null, name);
        Category updated = categoryRepository.save(row);
        return CategoryMutationResult.success(toView(updated));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CategoryMutationResult deleteCategory(UUID id) {
        Optional<Category> addressed = id == null ? Optional.empty() : categoryRepository.findById(id);
        if (addressed.isEmpty()) {
            return CategoryMutationResult.failure(List.of(new CategoryMutationError(
                    "id", CategoryMutationErrorCode.NOT_FOUND, "No category found for the id")));
        }
        Category row = addressed.get();
        CategoryView lastKnown = toView(row);
        categoryRepository.delete(row);
        return CategoryMutationResult.success(lastKnown);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public int deleteCategories(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (UUID id : ids) {
            // Unknown ids are skipped — the honest affected count answers
            // what the registry itself deleted (never a fabricated count,
            // never an error entry for an id the registry never knew).
            Optional<Category> addressed = id == null ? Optional.empty() : categoryRepository.findById(id);
            if (addressed.isPresent()) {
                categoryRepository.delete(addressed.get());
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * The new row's display position — after the registry's current tail
     * (the platform extension column's honest deterministic choice for a
     * create; a reorder capability is its own future root). The tail is
     * the HIGHEST live position plus one (the wave-19 #808 review: after
     * any mid-registry delete a size+1 derivation would collide with the
     * last row's own position and make the position order non-
     * deterministic between them).
     */
    private int nextPosition() {
        return categoryRepository.findAllByOrderByPositionAscIdAsc().stream()
                .mapToInt(Category::getPosition)
                .max().orElse(0) + 1;
    }

    /** The bounded projection — the read service's own rendering rule. */
    private static CategoryView toView(Category category) {
        String name = category.getNameEn() != null ? category.getNameEn() : category.getNameAr();
        return new CategoryView(category.getId(), name, category.getNameAr(),
                category.getCode(), category.getPosition());
    }
}
