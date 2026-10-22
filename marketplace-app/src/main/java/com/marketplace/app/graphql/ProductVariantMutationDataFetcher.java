package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.ProductError;
import com.marketplace.app.graphql.generated.types.ProductErrorCode;
import com.marketplace.app.graphql.generated.types.ProductVariant;
import com.marketplace.app.graphql.generated.types.ProductVariantBulkDelete;
import com.marketplace.app.graphql.generated.types.ProductVariantCreate;
import com.marketplace.app.graphql.generated.types.ProductVariantCreateInput;
import com.marketplace.app.graphql.generated.types.ProductVariantDelete;
import com.marketplace.app.graphql.generated.types.ProductVariantInput;
import com.marketplace.app.graphql.generated.types.ProductVariantUpdate;
import com.marketplace.catalog.spi.ProductVariantCreateCommand;
import com.marketplace.catalog.spi.ProductVariantMutationError;
import com.marketplace.catalog.spi.ProductVariantMutationErrorCode;
import com.marketplace.catalog.spi.ProductVariantMutationResult;
import com.marketplace.catalog.spi.ProductVariantUpdateCommand;
import com.marketplace.catalog.spi.ProductVariantWriteSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2): the GraphQL adapter for the
 * Saleor Products-domain {@code productVariantCreate},
 * {@code productVariantUpdate}, {@code productVariantDelete} and
 * {@code productVariantBulkDelete} mutation roots — over the catalog
 * owner's variant write use cases through the declared
 * {@code ProductVariantWriteSpi} seam. This class owns ONLY transport
 * mapping: typed global-ID decode, the bounded input-to-command
 * mapping and the domain result onto Saleor's payload shapes.
 * Authorization lives in the owning use case
 * ({@code @PreAuthorize("hasRole('PROVIDER')")} — the two-sided
 * marketplace's own ownership law for provider-owned rows: the owning
 * provider IS the caller, the row's denormalized providerId resolving
 * through the caller's authentication), exactly as the
 * {@code ProductMutationDataFetcher} slice established.
 *
 * <p><b>The payload-error contract is the registered "Mutation payload
 * errors" ADAPT divergence:</b> domain-level validation outcomes ride
 * the payload's own {@code errors} field (mapped from the owner's
 * {@code ProductVariantMutationError} entries onto the
 * {@code ProductError} vocabulary); transport-level validation — a
 * malformed or mistyped global ID, a keyless call — answers the
 * classified VAL-001 GraphQL error family the read roots established.
 * Declared-but-unowned arguments ({@code externalReference} — no
 * external-ID column; {@code sku}/{@code skus} — the id is the
 * addressing key on this platform, the SKU is seller-namespaced)
 * answer the explicit classified error when sent, never a silent
 * no-op.</p>
 */
@DgsComponent
public class ProductVariantMutationDataFetcher {

    private final ProductVariantWriteSpi variantWriteSpi;
    private final ProductVariantGraphQlMapper variantMapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public ProductVariantMutationDataFetcher(ProductVariantWriteSpi variantWriteSpi,
                                             ProductVariantGraphQlMapper variantMapper,
                                             SaleorGlobalIdCodec globalIdCodec) {
        this.variantWriteSpi = variantWriteSpi;
        this.variantMapper = variantMapper;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code productVariantCreate(input:
     * ProductVariantCreateInput!): ProductVariantCreate} — the variant
     * registration over the owner's use case. The input's required
     * {@code product} reference is a typed global ID decoded at this
     * boundary (a malformed or mistyped ID answers the classified
     * VAL-001 family). The ownership facts (provider, currency) are
     * the owner's own copy from the parent, never client-supplied; the
     * stock starts at zero (the inventory surface is its own root).
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductVariantCreate)
    @RateLimiter(name = "graphqlProductVariantMutation")
    public ProductVariantCreate productVariantCreate(@InputArgument ProductVariantCreateInput input) {
        requireInput(input, "productVariantCreate");
        UUID productId = input.getProduct() == null
                ? null
                : globalIdCodec.decode(input.getProduct(), ProductGraphQlMapper.PRODUCT_TYPE);
        ProductVariantMutationResult result = variantWriteSpi.createVariant(
                new ProductVariantCreateCommand(productId, input.getSku(), input.getName(),
                        toLongMinor(input.getPriceMinor())),
                currentAuthentication());
        return ProductVariantCreate.newBuilder()
                .errors(toProductErrors(result.errors()))
                .productVariant(result.variant() == null
                        ? null : variantMapper.toGraphQlProductVariant(result.variant()))
                .build();
    }

    /**
     * Saleor {@code productVariantUpdate(externalReference: String,
     * id: ID, input: ProductVariantInput!, sku: String):
     * ProductVariantUpdate} — the variant amendment. The {@code id}
     * argument executes through the typed global-ID codec; the
     * {@code externalReference} argument has no owning column and the
     * {@code sku} argument is not an addressing key on this platform
     * (the id is) — both answer the classified error when sent.
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductVariantUpdate)
    @RateLimiter(name = "graphqlProductVariantMutation")
    public ProductVariantUpdate productVariantUpdate(@InputArgument String externalReference,
                                                     @InputArgument String id,
                                                     @InputArgument ProductVariantInput input,
                                                     @InputArgument String sku) {
        requireInput(input, "productVariantUpdate");
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "productVariantUpdate externalReference is not implemented on this platform yet");
        }
        if (sku != null) {
            throw new IllegalArgumentException(
                    "productVariantUpdate sku addressing is not implemented on this platform yet"
                            + " (the id is the addressing key)");
        }
        if (id == null) {
            throw new IllegalArgumentException("productVariantUpdate requires the id of the variant to update");
        }
        UUID nativeId = globalIdCodec.decode(id, ProductVariantGraphQlMapper.PRODUCT_VARIANT_TYPE);
        ProductVariantMutationResult result = variantWriteSpi.updateVariant(
                nativeId, new ProductVariantUpdateCommand(input.getSku(), input.getName(),
                        toLongMinor(input.getPriceMinor())),
                currentAuthentication());
        return ProductVariantUpdate.newBuilder()
                .errors(toProductErrors(result.errors()))
                .productVariant(result.variant() == null
                        ? null : variantMapper.toGraphQlProductVariant(result.variant()))
                .build();
    }

    /**
     * Saleor {@code productVariantDelete(externalReference: String,
     * id: ID, sku: String): ProductVariantDelete} — the variant soft
     * delete. The {@code id} argument executes through the typed
     * global-ID codec; a keyless call answers the classified
     * validation error (never a fabricated absence); the unowned
     * addressing arguments answer the classified error when sent.
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductVariantDelete)
    @RateLimiter(name = "graphqlProductVariantMutation")
    public ProductVariantDelete productVariantDelete(@InputArgument String externalReference,
                                                     @InputArgument String id,
                                                     @InputArgument String sku) {
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "productVariantDelete externalReference is not implemented on this platform yet");
        }
        if (sku != null) {
            throw new IllegalArgumentException(
                    "productVariantDelete sku addressing is not implemented on this platform yet"
                            + " (the id is the addressing key)");
        }
        if (id == null) {
            throw new IllegalArgumentException("productVariantDelete requires the id of the variant to delete");
        }
        UUID nativeId = globalIdCodec.decode(id, ProductVariantGraphQlMapper.PRODUCT_VARIANT_TYPE);
        ProductVariantMutationResult result = variantWriteSpi.deleteVariant(nativeId, currentAuthentication());
        return ProductVariantDelete.newBuilder()
                .errors(toProductErrors(result.errors()))
                .productVariant(result.variant() == null
                        ? null : variantMapper.toGraphQlProductVariant(result.variant()))
                .build();
    }

    /**
     * Saleor {@code productVariantBulkDelete(ids: [ID!], skus:
     * [String!]): ProductVariantBulkDelete} — the variant bulk soft
     * delete. Every id executes through the typed global-ID codec (one
     * malformed id fails the whole call — the classified VAL-001
     * family); the {@code skus} argument is not an addressing key on
     * this platform and answers the classified error when sent;
     * unknown or stranger ids are the owner's own honest skip, and the
     * count answers what the caller owns and the registry itself
     * deleted.
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductVariantBulkDelete)
    @RateLimiter(name = "graphqlProductVariantMutation")
    public ProductVariantBulkDelete productVariantBulkDelete(@InputArgument List<String> ids,
                                                             @InputArgument List<String> skus) {
        if (skus != null && !skus.isEmpty()) {
            throw new IllegalArgumentException(
                    "productVariantBulkDelete skus addressing is not implemented on this platform yet"
                            + " (the ids are the addressing keys)");
        }
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("productVariantBulkDelete requires a non-empty list of ids");
        }
        List<UUID> nativeIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            nativeIds.add(globalIdCodec.decode(id, ProductVariantGraphQlMapper.PRODUCT_VARIANT_TYPE));
        }
        return ProductVariantBulkDelete.newBuilder()
                .count(variantWriteSpi.deleteVariants(nativeIds, currentAuthentication()))
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
     * the security context (the {@code ProductMutationDataFetcher}
     * seam's own discipline).
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
    private static List<ProductError> toProductErrors(List<ProductVariantMutationError> errors) {
        List<ProductError> mapped = new ArrayList<>(errors.size());
        for (ProductVariantMutationError error : errors) {
            mapped.add(ProductError.newBuilder()
                    .field(error.field())
                    .message(error.message())
                    .code(toProductErrorCode(error.code()))
                    .build());
        }
        return mapped;
    }

    /** The domain code onto the Saleor enum's own member. */
    private static ProductErrorCode toProductErrorCode(ProductVariantMutationErrorCode code) {
        return switch (code) {
            case REQUIRED -> ProductErrorCode.REQUIRED;
            case INVALID -> ProductErrorCode.INVALID;
            case NOT_FOUND -> ProductErrorCode.NOT_FOUND;
            case ALREADY_EXISTS -> ProductErrorCode.ALREADY_EXISTS;
        };
    }
}
