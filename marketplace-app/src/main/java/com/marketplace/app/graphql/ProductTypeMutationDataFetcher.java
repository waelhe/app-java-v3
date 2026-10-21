package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.ProductError;
import com.marketplace.app.graphql.generated.types.ProductErrorCode;
import com.marketplace.app.graphql.generated.types.ProductTypeBulkDelete;
import com.marketplace.app.graphql.generated.types.ProductTypeCreate;
import com.marketplace.app.graphql.generated.types.ProductTypeDelete;
import com.marketplace.app.graphql.generated.types.ProductTypeInput;
import com.marketplace.app.graphql.generated.types.ProductTypeUpdate;
import com.marketplace.catalog.spi.ProductTypeCreateCommand;
import com.marketplace.catalog.spi.ProductTypeMutationError;
import com.marketplace.catalog.spi.ProductTypeMutationErrorCode;
import com.marketplace.catalog.spi.ProductTypeMutationResult;
import com.marketplace.catalog.spi.ProductTypeUpdateCommand;
import com.marketplace.catalog.spi.ProductTypeWriteSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2): the GraphQL adapter for the
 * Saleor Products-domain {@code productTypeCreate},
 * {@code productTypeUpdate}, {@code productTypeDelete} and
 * {@code productTypeBulkDelete} mutation roots — over the catalog
 * owner's write use cases through the declared {@code ProductTypeWriteSpi}
 * seam. This class owns ONLY transport mapping: typed global-ID decode,
 * the bounded input-to-command mapping and the domain result onto
 * Saleor's payload shapes. Authorization lives in the owning use case
 * ({@code @PreAuthorize("hasRole('ADMIN')")} — the
 * MANAGE_PRODUCT_TYPES_AND_ATTRIBUTES root's platform capability),
 * exactly as the category registry's write use case established.
 *
 * <p><b>The payload-error contract is the registered "Mutation payload
 * errors" ADAPT divergence:</b> domain-level validation outcomes ride
 * the payload's own {@code errors} field (mapped from the owner's
 * {@code ProductTypeMutationError} entries); transport-level
 * validation — a malformed or mistyped global ID, a keyless call —
 * answers the classified VAL-001 GraphQL error family the read roots
 * established. The pinned root's own REQUIRED {@code id} arguments
 * ({@code ID!}) make the keyless call a GraphQL-layer refusal; the
 * boundary mirror keeps the same classified answer for programmatic
 * callers.</p>
 */
@DgsComponent
public class ProductTypeMutationDataFetcher {

    private final ProductTypeWriteSpi productTypeWriteSpi;
    private final ProductTypeGraphQlMapper productTypeMapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public ProductTypeMutationDataFetcher(ProductTypeWriteSpi productTypeWriteSpi,
                                          ProductTypeGraphQlMapper productTypeMapper,
                                          SaleorGlobalIdCodec globalIdCodec) {
        this.productTypeWriteSpi = productTypeWriteSpi;
        this.productTypeMapper = productTypeMapper;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code productTypeCreate(input: ProductTypeInput!):
     * ProductTypeCreate} — the goods-schema registration over the
     * owner's use case.
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductTypeCreate)
    @RateLimiter(name = "graphqlProductTypeMutation")
    public ProductTypeCreate productTypeCreate(@InputArgument ProductTypeInput input) {
        requireInput(input, "productTypeCreate");
        ProductTypeMutationResult result = productTypeWriteSpi.createProductType(
                new ProductTypeCreateCommand(input.getName(), input.getSlug()));
        return ProductTypeCreate.newBuilder()
                .errors(toProductErrors(result.errors()))
                .productType(result.productType() == null
                        ? null
                        : productTypeMapper.toGraphQlProductType(result.productType()))
                .build();
    }

    /**
     * Saleor {@code productTypeUpdate(id: ID!, input: ProductTypeInput!):
     * ProductTypeUpdate} — the goods-schema amendment. The {@code id}
     * argument executes through the typed global-ID codec (a malformed
     * or mistyped ID answers the classified VAL-001 family).
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductTypeUpdate)
    @RateLimiter(name = "graphqlProductTypeMutation")
    public ProductTypeUpdate productTypeUpdate(@InputArgument String id,
                                               @InputArgument ProductTypeInput input) {
        requireInput(input, "productTypeUpdate");
        if (id == null) {
            throw new IllegalArgumentException("productTypeUpdate requires the id of the product type to update");
        }
        UUID nativeId = globalIdCodec.decode(id, ProductTypeGraphQlMapper.PRODUCT_TYPE_TYPE);
        ProductTypeMutationResult result = productTypeWriteSpi.updateProductType(
                nativeId, new ProductTypeUpdateCommand(input.getName(), input.getSlug()));
        return ProductTypeUpdate.newBuilder()
                .errors(toProductErrors(result.errors()))
                .productType(result.productType() == null
                        ? null
                        : productTypeMapper.toGraphQlProductType(result.productType()))
                .build();
    }

    /**
     * Saleor {@code productTypeDelete(id: ID!): ProductTypeDelete} — the
     * goods-schema soft delete. The {@code id} argument executes through
     * the typed global-ID codec; a keyless call answers the classified
     * validation error (never a fabricated absence).
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductTypeDelete)
    @RateLimiter(name = "graphqlProductTypeMutation")
    public ProductTypeDelete productTypeDelete(@InputArgument String id) {
        if (id == null) {
            throw new IllegalArgumentException("productTypeDelete requires the id of the product type to delete");
        }
        UUID nativeId = globalIdCodec.decode(id, ProductTypeGraphQlMapper.PRODUCT_TYPE_TYPE);
        ProductTypeMutationResult result = productTypeWriteSpi.deleteProductType(nativeId);
        return ProductTypeDelete.newBuilder()
                .errors(toProductErrors(result.errors()))
                .productType(result.productType() == null
                        ? null
                        : productTypeMapper.toGraphQlProductType(result.productType()))
                .build();
    }

    /**
     * Saleor {@code productTypeBulkDelete(ids: [ID!]!):
     * ProductTypeBulkDelete} — the goods-schema bulk soft delete. Every
     * id executes through the typed global-ID codec (one malformed id
     * fails the whole call — the classified VAL-001 family);
     * unknown-but-well-formed ids are the owner's own honest skip, and
     * the count answers what the registry itself deleted.
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductTypeBulkDelete)
    @RateLimiter(name = "graphqlProductTypeMutation")
    public ProductTypeBulkDelete productTypeBulkDelete(@InputArgument List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("productTypeBulkDelete requires a non-empty list of ids");
        }
        List<UUID> nativeIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            nativeIds.add(globalIdCodec.decode(id, ProductTypeGraphQlMapper.PRODUCT_TYPE_TYPE));
        }
        return ProductTypeBulkDelete.newBuilder()
                .count(productTypeWriteSpi.deleteProductTypes(nativeIds))
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
    private static List<ProductError> toProductErrors(List<ProductTypeMutationError> errors) {
        List<ProductError> mapped = new ArrayList<>(errors.size());
        for (ProductTypeMutationError error : errors) {
            mapped.add(ProductError.newBuilder()
                    .field(error.field())
                    .message(error.message())
                    .code(toProductErrorCode(error.code()))
                    .build());
        }
        return mapped;
    }

    /** The domain code onto the Saleor enum's own member. */
    private static ProductErrorCode toProductErrorCode(ProductTypeMutationErrorCode code) {
        return switch (code) {
            case REQUIRED -> ProductErrorCode.REQUIRED;
            case INVALID -> ProductErrorCode.INVALID;
            case NOT_FOUND -> ProductErrorCode.NOT_FOUND;
            case ALREADY_EXISTS -> ProductErrorCode.ALREADY_EXISTS;
        };
    }
}
