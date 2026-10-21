package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.CategoryBulkDelete;
import com.marketplace.app.graphql.generated.types.CategoryCreate;
import com.marketplace.app.graphql.generated.types.CategoryDelete;
import com.marketplace.app.graphql.generated.types.CategoryInput;
import com.marketplace.app.graphql.generated.types.CategoryUpdate;
import com.marketplace.app.graphql.generated.types.ProductError;
import com.marketplace.app.graphql.generated.types.ProductErrorCode;
import com.marketplace.catalog.spi.CategoryCreateCommand;
import com.marketplace.catalog.spi.CategoryMutationError;
import com.marketplace.catalog.spi.CategoryMutationErrorCode;
import com.marketplace.catalog.spi.CategoryMutationResult;
import com.marketplace.catalog.spi.CategoryUpdateCommand;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2): the GraphQL adapter for the
 * Saleor Products-domain {@code categoryCreate}, {@code categoryUpdate},
 * {@code categoryDelete} and {@code categoryBulkDelete} mutation roots —
 * over the catalog owner's write use cases through the declared
 * {@code CategoryWriteSpi} seam. This class owns ONLY transport mapping:
 * typed global-ID decode, the bounded input-to-command mapping and the
 * domain result onto Saleor's payload shapes. Authorization lives in the
 * owning use case ({@code @PreAuthorize("hasRole('ADMIN')")} — the
 * MANAGE_PRODUCTS root's platform capability), exactly as the pricing
 * module's discount reads established.
 *
 * <p><b>The payload-error contract is the registered "Mutation payload
 * errors" ADAPT divergence:</b> domain-level validation outcomes ride
 * the payload's own {@code errors} field (mapped from the owner's
 * {@code CategoryMutationError} entries); transport-level validation —
 * a malformed or mistyped global ID, a keyless call — answers the
 * classified VAL-001 GraphQL error family the read roots established,
 * and a denied authorization answers Spring Security's own 403 problem.
 * Declared-but-unowned arguments ({@code parent} — no nesting tree
 * owner; {@code externalReference} — no external-ID column) answer the
 * explicit classified error when sent, never a silent no-op.</p>
 */
@DgsComponent
public class CategoryMutationDataFetcher {

    private final CategoryWriteSpi categoryWriteSpi;
    private final CategoryGraphQlMapper categoryMapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public CategoryMutationDataFetcher(CategoryWriteSpi categoryWriteSpi,
                                       CategoryGraphQlMapper categoryMapper,
                                       SaleorGlobalIdCodec globalIdCodec) {
        this.categoryWriteSpi = categoryWriteSpi;
        this.categoryMapper = categoryMapper;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code categoryCreate(input: CategoryInput!, parent: ID):
     * CategoryCreate} — the registry write over the owner's use case.
     * The {@code parent} argument has no nesting-tree owner on this
     * platform (the flat registry's own recorded divergence): a non-null
     * value answers the classified validation error, never a silently
     * ignored argument.
     */
    @DgsMutation(field = DgsConstants.MUTATION.CategoryCreate)
    @RateLimiter(name = "graphqlCategoryQuery")
    public CategoryCreate categoryCreate(@InputArgument CategoryInput input,
                                         @InputArgument String parent) {
        requireInput(input, "categoryCreate");
        if (parent != null) {
            throw new IllegalArgumentException(
                    "categoryCreate parent is not implemented on this platform yet (the registry is flat)");
        }
        CategoryMutationResult result = categoryWriteSpi.createCategory(
                new CategoryCreateCommand(input.getName(), input.getSlug()));
        return CategoryCreate.newBuilder()
                .errors(toProductErrors(result.errors()))
                .category(result.category() == null ? null : categoryMapper.toGraphQlCategory(result.category()))
                .build();
    }

    /**
     * Saleor {@code categoryUpdate(externalReference: String, id: ID,
     * input: CategoryInput!): CategoryUpdate} — the registry amendment.
     * The {@code id} argument executes through the typed global-ID codec
     * (a malformed or mistyped ID answers the classified VAL-001 family);
     * the {@code externalReference} argument has no owning column and
     * answers the classified error when sent.
     */
    @DgsMutation(field = DgsConstants.MUTATION.CategoryUpdate)
    @RateLimiter(name = "graphqlCategoryQuery")
    public CategoryUpdate categoryUpdate(@InputArgument String externalReference,
                                         @InputArgument String id,
                                         @InputArgument CategoryInput input) {
        requireInput(input, "categoryUpdate");
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "categoryUpdate externalReference is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException("categoryUpdate requires the id of the category to update");
        }
        UUID nativeId = globalIdCodec.decode(id, CategoryGraphQlMapper.CATEGORY_TYPE);
        CategoryMutationResult result = categoryWriteSpi.updateCategory(
                nativeId, new CategoryUpdateCommand(input.getName(), input.getSlug()));
        return CategoryUpdate.newBuilder()
                .errors(toProductErrors(result.errors()))
                .category(result.category() == null ? null : categoryMapper.toGraphQlCategory(result.category()))
                .build();
    }

    /**
     * Saleor {@code categoryDelete(externalReference: String, id: ID):
     * CategoryDelete} — the registry soft delete. The {@code id}
     * argument executes through the typed global-ID codec; a keyless
     * call answers the classified validation error (never a fabricated
     * absence).
     */
    @DgsMutation(field = DgsConstants.MUTATION.CategoryDelete)
    @RateLimiter(name = "graphqlCategoryQuery")
    public CategoryDelete categoryDelete(@InputArgument String externalReference,
                                         @InputArgument String id) {
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "categoryDelete externalReference is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException("categoryDelete requires the id of the category to delete");
        }
        UUID nativeId = globalIdCodec.decode(id, CategoryGraphQlMapper.CATEGORY_TYPE);
        CategoryMutationResult result = categoryWriteSpi.deleteCategory(nativeId);
        return CategoryDelete.newBuilder()
                .errors(toProductErrors(result.errors()))
                .category(result.category() == null ? null : categoryMapper.toGraphQlCategory(result.category()))
                .build();
    }

    /**
     * Saleor {@code categoryBulkDelete(ids: [ID!]!): CategoryBulkDelete} —
     * the registry bulk soft delete. Every id executes through the typed
     * global-ID codec (one malformed id fails the whole call — the
     * classified VAL-001 family); unknown-but-well-formed ids are the
     * owner's own honest skip, and the count answers what the registry
     * itself deleted.
     */
    @DgsMutation(field = DgsConstants.MUTATION.CategoryBulkDelete)
    @RateLimiter(name = "graphqlCategoryQuery")
    public CategoryBulkDelete categoryBulkDelete(@InputArgument List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("categoryBulkDelete requires a non-empty list of ids");
        }
        List<UUID> nativeIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            nativeIds.add(globalIdCodec.decode(id, CategoryGraphQlMapper.CATEGORY_TYPE));
        }
        return CategoryBulkDelete.newBuilder()
                .count(categoryWriteSpi.deleteCategories(nativeIds))
                .errors(List.of())
                .build();
    }

    /**
     * The input presence guard — the SDL's own {@code input: ...!}
     * contract mirrored at the boundary (a null input is a client error,
     * never a NullPointerException).
     */
    private static void requireInput(Object input, String rootName) {
        if (input == null) {
            throw new IllegalArgumentException(rootName + " requires a non-null input");
        }
    }

    /**
     * The domain error entries onto the Saleor payload error objects —
     * the registered ADAPT mapping (the codes are the shared enum
     * members; the field/message ride verbatim).
     */
    private static List<ProductError> toProductErrors(List<CategoryMutationError> errors) {
        List<ProductError> mapped = new ArrayList<>(errors.size());
        for (CategoryMutationError error : errors) {
            mapped.add(ProductError.newBuilder()
                    .field(error.field())
                    .message(error.message())
                    .code(toProductErrorCode(error.code()))
                    .build());
        }
        return mapped;
    }

    /** The domain code onto the Saleor enum's own member. */
    private static ProductErrorCode toProductErrorCode(CategoryMutationErrorCode code) {
        return switch (code) {
            case REQUIRED -> ProductErrorCode.REQUIRED;
            case INVALID -> ProductErrorCode.INVALID;
            case NOT_FOUND -> ProductErrorCode.NOT_FOUND;
            case ALREADY_EXISTS -> ProductErrorCode.ALREADY_EXISTS;
        };
    }
}
