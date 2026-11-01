package com.marketplace.catalog;

import com.marketplace.catalog.spi.CategoryCreateCommand;
import com.marketplace.catalog.spi.CategoryMutationError;
import com.marketplace.catalog.spi.CategoryMutationErrorCode;
import com.marketplace.catalog.spi.CategoryMutationResult;
import com.marketplace.catalog.spi.CategoryUpdateCommand;
import com.marketplace.catalog.spi.CategoryView;
import com.marketplace.catalog.spi.CategoryWriteSpi;
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
 * shape), and the {@code uq_categories_code_active} partial unique index
 * keeps ACTIVE rows unique while a soft-deleted row releases its code
 * for reuse (the V47 slug precedent). The service re-checks both BEFORE
 * the write (the honest precheck over the derived {@code findByCode}
 * lookup) and lets the partial unique index remain the concurrent-write
 * backstop — the same no-TOCTOU stance the repository's own javadoc
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

    private final CategoryRepository categoryRepository;

    public CategoryWriteService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
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
        } else if (categoryRepository.findByCode(code).isPresent()) {
            errors.add(new CategoryMutationError("slug", CategoryMutationErrorCode.ALREADY_EXISTS,
                    "A category with the slug already exists"));
        }
        if (!errors.isEmpty()) {
            return CategoryMutationResult.failure(errors);
        }
        Category created = categoryRepository.save(
                Category.create(code, name, null, nextPosition()));
        return CategoryMutationResult.success(toView(created));
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
            } else {
                categoryRepository.findByCode(code)
                        .filter(existing -> !existing.getId().equals(id))
                        .ifPresent(existing -> errors.add(new CategoryMutationError(
                                "slug", CategoryMutationErrorCode.ALREADY_EXISTS,
                                "A category with the slug already exists")));
            }
        }
        if (!errors.isEmpty()) {
            return CategoryMutationResult.failure(errors);
        }
        Category row = addressed.get();
        row.revise(code, name);
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
     * create; a reorder capability is its own future root).
     */
    private int nextPosition() {
        return categoryRepository.findAllByOrderByPositionAsc().size() + 1;
    }

    /** The bounded projection — the read service's own rendering rule. */
    private static CategoryView toView(Category category) {
        String name = category.getNameEn() != null ? category.getNameEn() : category.getNameAr();
        return new CategoryView(category.getId(), name, category.getNameAr(),
                category.getCode(), category.getPosition());
    }
}
