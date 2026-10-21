package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.ProductBulkDelete;
import com.marketplace.app.graphql.generated.types.ProductCreate;
import com.marketplace.app.graphql.generated.types.ProductCreateInput;
import com.marketplace.app.graphql.generated.types.ProductDelete;
import com.marketplace.app.graphql.generated.types.ProductError;
import com.marketplace.app.graphql.generated.types.ProductErrorCode;
import com.marketplace.app.graphql.generated.types.ProductInput;
import com.marketplace.app.graphql.generated.types.ProductUpdate;
import com.marketplace.catalog.spi.ProductCreateCommand;
import com.marketplace.catalog.spi.ProductMutationError;
import com.marketplace.catalog.spi.ProductMutationErrorCode;
import com.marketplace.catalog.spi.ProductMutationResult;
import com.marketplace.catalog.spi.ProductUpdateCommand;
import com.marketplace.catalog.spi.ProductWriteSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2): the GraphQL adapter for the
 * Saleor Products-domain {@code productCreate}, {@code productUpdate},
 * {@code productDelete} and {@code productBulkDelete} mutation roots —
 * over the catalog owner's write use cases through the declared
 * {@code ProductWriteSpi} seam. This class owns ONLY transport
 * mapping: typed global-ID decode, the bounded input-to-command
 * mapping and the domain result onto Saleor's payload shapes.
 * Authorization lives in the owning use case
 * ({@code @PreAuthorize("hasRole('PROVIDER')")} — the two-sided
 * marketplace's own ownership law for provider-owned rows: the owning
 * provider IS the caller), exactly as the module's own REST write
 * paths established.
 *
 * <p><b>The payload-error contract is the registered "Mutation payload
 * errors" ADAPT divergence:</b> domain-level validation outcomes ride
 * the payload's own {@code errors} field (mapped from the owner's
 * {@code ProductMutationError} entries); transport-level validation —
 * a malformed or mistyped global ID, a keyless call — answers the
 * classified VAL-001 GraphQL error family the read roots established.
 * Declared-but-unowned arguments ({@code externalReference} — no
 * external-ID column) answer the explicit classified error when sent,
 * never a silent no-op.</p>
 */
@DgsComponent
public class ProductMutationDataFetcher {

    private final ProductWriteSpi productWriteSpi;
    private final ProductGraphQlMapper productMapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public ProductMutationDataFetcher(ProductWriteSpi productWriteSpi,
                                      ProductGraphQlMapper productMapper,
                                      SaleorGlobalIdCodec globalIdCodec) {
        this.productWriteSpi = productWriteSpi;
        this.productMapper = productMapper;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code productCreate(input: ProductCreateInput!):
     * ProductCreate} — the product registration over the owner's use
     * case. The input's optional {@code productType} reference is a
     * typed global ID decoded at this boundary (a malformed or
     * mistyped ID answers the classified VAL-001 family).
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductCreate)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public ProductCreate productCreate(@InputArgument ProductCreateInput input) {
        requireInput(input, "productCreate");
        UUID productTypeId = input.getProductType() == null
                ? null
                : globalIdCodec.decode(input.getProductType(), ProductGraphQlMapper.PRODUCT_TYPE_REFERENCE);
        ProductMutationResult result = productWriteSpi.createProduct(
                new ProductCreateCommand(input.getName(), input.getDescription(),
                        input.getStoreCategoryCode(), toLongMinor(input.getPriceMinor()),
                        input.getCurrency(), productTypeId),
                currentAuthentication());
        return ProductCreate.newBuilder()
                .errors(toProductErrors(result.errors()))
                .product(result.product() == null ? null : productMapper.toGraphQlProduct(result.product()))
                .build();
    }

    /**
     * Saleor {@code productUpdate(externalReference: String, id: ID,
     * input: ProductInput!): ProductUpdate} — the product amendment.
     * The {@code id} argument executes through the typed global-ID
     * codec; the {@code externalReference} argument has no owning
     * column and answers the classified error when sent (the id is the
     * addressing key on this platform).
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductUpdate)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public ProductUpdate productUpdate(@InputArgument String externalReference,
                                       @InputArgument String id,
                                       @InputArgument ProductInput input) {
        requireInput(input, "productUpdate");
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "productUpdate externalReference is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException("productUpdate requires the id of the product to update");
        }
        UUID nativeId = globalIdCodec.decode(id, ProductGraphQlMapper.PRODUCT_TYPE);
        ProductMutationResult result = productWriteSpi.updateProduct(
                nativeId, new ProductUpdateCommand(input.getName(), input.getDescription(),
                        toLongMinor(input.getPriceMinor())),
                currentAuthentication());
        return ProductUpdate.newBuilder()
                .errors(toProductErrors(result.errors()))
                .product(result.product() == null ? null : productMapper.toGraphQlProduct(result.product()))
                .build();
    }

    /**
     * Saleor {@code productDelete(externalReference: String, id: ID):
     * ProductDelete} — the product soft delete. The {@code id}
     * argument executes through the typed global-ID codec; a keyless
     * call answers the classified validation error (never a fabricated
     * absence).
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductDelete)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public ProductDelete productDelete(@InputArgument String externalReference,
                                       @InputArgument String id) {
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "productDelete externalReference is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException("productDelete requires the id of the product to delete");
        }
        UUID nativeId = globalIdCodec.decode(id, ProductGraphQlMapper.PRODUCT_TYPE);
        ProductMutationResult result = productWriteSpi.deleteProduct(nativeId, currentAuthentication());
        return ProductDelete.newBuilder()
                .errors(toProductErrors(result.errors()))
                .product(result.product() == null ? null : productMapper.toGraphQlProduct(result.product()))
                .build();
    }

    /**
     * Saleor {@code productBulkDelete(ids: [ID!]!): ProductBulkDelete}
     * — the product bulk soft delete. Every id executes through the
     * typed global-ID codec (one malformed id fails the whole call —
     * the classified VAL-001 family); unknown or stranger ids are the
     * owner's own honest skip, and the count answers what the caller
     * owns and the registry itself deleted.
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductBulkDelete)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public ProductBulkDelete productBulkDelete(@InputArgument List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("productBulkDelete requires a non-empty list of ids");
        }
        List<UUID> nativeIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            nativeIds.add(globalIdCodec.decode(id, ProductGraphQlMapper.PRODUCT_TYPE));
        }
        return ProductBulkDelete.newBuilder()
                .count(productWriteSpi.deleteProducts(nativeIds, currentAuthentication()))
                .errors(List.of())
                .build();
    }

    /**
     * The GraphQL Int's own widening onto the row's long minor units —
     * the codegen's Integer boundary meeting the entity's long fact.
     */
    private static Long toLongMinor(Integer minor) {
        return minor == null ? null : minor.longValue();
    }

    /**
     * The caller's authentication — the owning provider resolves from
     * the security context (the {@code StoreProductDataFetcher} seam's
     * own discipline).
     */
    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Authentication is required for catalog administration");
        }
        return authentication;
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
    private static List<ProductError> toProductErrors(List<ProductMutationError> errors) {
        List<ProductError> mapped = new ArrayList<>(errors.size());
        for (ProductMutationError error : errors) {
            mapped.add(ProductError.newBuilder()
                    .field(error.field())
                    .message(error.message())
                    .code(toProductErrorCode(error.code()))
                    .build());
        }
        return mapped;
    }

    /** The domain code onto the Saleor enum's own member. */
    private static ProductErrorCode toProductErrorCode(ProductMutationErrorCode code) {
        return switch (code) {
            case REQUIRED -> ProductErrorCode.REQUIRED;
            case INVALID -> ProductErrorCode.INVALID;
            case NOT_FOUND -> ProductErrorCode.NOT_FOUND;
        };
    }
}
