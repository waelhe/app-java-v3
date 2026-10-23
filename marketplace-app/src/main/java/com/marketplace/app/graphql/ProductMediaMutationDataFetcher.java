package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.ProductMedia;
import com.marketplace.app.graphql.generated.types.ProductMediaBulkDelete;
import com.marketplace.app.graphql.generated.types.ProductMediaDelete;
import com.marketplace.app.graphql.generated.types.ProductMediaReorder;
import com.marketplace.app.graphql.generated.types.ProductError;
import com.marketplace.app.graphql.generated.types.ProductErrorCode;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.media.spi.ProductMediaBulkResult;
import com.marketplace.media.spi.ProductMediaMutationError;
import com.marketplace.media.spi.ProductMediaMutationErrorCode;
import com.marketplace.media.spi.ProductMediaMutationResult;
import com.marketplace.media.spi.ProductMediaReorderResult;
import com.marketplace.media.spi.ProductMediaView;
import com.marketplace.media.spi.ProductMediaWriteSpi;
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
 * N-slice (the canonical Saleor plan §5-P2): the GraphQL adapter for
 * the Saleor Products-domain {@code productMediaDelete},
 * {@code productMediaBulkDelete} and {@code productMediaReorder}
 * mutation roots, packet agent-2 — over the media owner's write use
 * cases through the declared {@code ProductMediaWriteSpi} seam. This
 * class owns ONLY transport mapping: typed global-ID decode, the
 * bounded result-to-payload mapping and the domain errors onto the
 * pinned payloads' own {@code ProductError} family (the pinned 3.23.43
 * shapes' own choice — the product family's shared error entries).
 * Authorization lives in the owning use case
 * ({@code @PreAuthorize("hasRole('PROVIDER')")} — the MANAGE_PRODUCTS
 * root's platform capability over provider-owned rows), exactly as
 * every write family in this packet established.
 *
 * <p><b>The payload-error contract is the registered "Mutation payload
 * errors" ADAPT divergence:</b> domain-level validation outcomes ride
 * the payload's own {@code errors} field; transport-level validation —
 * a malformed or mistyped global ID, a keyless call — answers the
 * classified VAL-001 GraphQL error family the read roots established,
 * and a denied authorization answers Spring Security's own 403
 * problem. The payload's {@code product} projection resolves through
 * the catalog owner's read seam (the product family's own mapper) —
 * the composition the packet's cross-module payloads already
 * carry.</p>
 */
@DgsComponent
public class ProductMediaMutationDataFetcher {

    /** The Saleor {@code ProductMedia} type's own global-ID tag. */
    static final String PRODUCT_MEDIA_TYPE = "ProductMedia";

    private final ProductMediaWriteSpi productMediaWriteSpi;
    private final ProductReadSpi productReadSpi;
    private final ProductGraphQlMapper productMapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public ProductMediaMutationDataFetcher(ProductMediaWriteSpi productMediaWriteSpi,
                                           ProductReadSpi productReadSpi,
                                           ProductGraphQlMapper productMapper,
                                           SaleorGlobalIdCodec globalIdCodec) {
        this.productMediaWriteSpi = productMediaWriteSpi;
        this.productReadSpi = productReadSpi;
        this.productMapper = productMapper;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code productMediaDelete(id: ID!): ProductMediaDelete} —
     * the media owner's deletion over its own seam. The {@code id}
     * argument executes through the typed global-ID codec; a keyless
     * call answers the classified validation error.
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductMediaDelete)
    @RateLimiter(name = "graphqlProductMediaMutation")
    public ProductMediaDelete productMediaDelete(@InputArgument String id) {
        if (id == null) {
            throw new IllegalArgumentException("productMediaDelete requires the id of the media to delete");
        }
        UUID nativeId = globalIdCodec.decode(id, PRODUCT_MEDIA_TYPE);
        ProductMediaMutationResult result = productMediaWriteSpi.deleteProductMedia(
                nativeId, currentAuthentication());
        return ProductMediaDelete.newBuilder()
                .errors(toProductErrors(result.errors()))
                .media(result.media() == null ? null : toGraphQlProductMedia(result.media()))
                .product(resolveProduct(result.productId()))
                .build();
    }

    /**
     * Saleor {@code productMediaBulkDelete(ids: [ID!]!):
     * ProductMediaBulkDelete} — the batch deletion over the same
     * single-item ladder. Every id executes through the typed
     * global-ID codec (one malformed id fails the whole call — the
     * classified VAL-001 family); unknown-but-well-formed ids are the
     * owner's own honest skip, and the count answers what the registry
     * itself deleted.
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductMediaBulkDelete)
    @RateLimiter(name = "graphqlProductMediaMutation")
    public ProductMediaBulkDelete productMediaBulkDelete(@InputArgument List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("productMediaBulkDelete requires a non-empty list of ids");
        }
        List<UUID> nativeIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            nativeIds.add(globalIdCodec.decode(id, PRODUCT_MEDIA_TYPE));
        }
        ProductMediaBulkResult result = productMediaWriteSpi.bulkDeleteProductMedia(
                nativeIds, currentAuthentication());
        return ProductMediaBulkDelete.newBuilder()
                .count(result.count())
                .errors(toProductErrors(result.errors()))
                .build();
    }

    /**
     * Saleor {@code productMediaReorder(mediaIds: [ID!]!, productId:
     * ID!): ProductMediaReorder} — the display-order rewrite over the
     * owner's use case. Both arguments execute through the typed
     * global-ID codec (a malformed or mistyped id answers the
     * classified VAL-001 family — never a silent skip).
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductMediaReorder)
    @RateLimiter(name = "graphqlProductMediaMutation")
    public ProductMediaReorder productMediaReorder(@InputArgument List<String> mediaIds,
                                                    @InputArgument String productId) {
        if (productId == null) {
            throw new IllegalArgumentException("productMediaReorder requires the productId");
        }
        if (mediaIds == null || mediaIds.isEmpty()) {
            throw new IllegalArgumentException("productMediaReorder requires a non-empty list of mediaIds");
        }
        UUID nativeProductId = globalIdCodec.decode(productId, ProductGraphQlMapper.PRODUCT_TYPE);
        List<UUID> nativeMediaIds = new ArrayList<>(mediaIds.size());
        for (String mediaId : mediaIds) {
            nativeMediaIds.add(globalIdCodec.decode(mediaId, PRODUCT_MEDIA_TYPE));
        }
        ProductMediaReorderResult result = productMediaWriteSpi.reorderProductMedia(
                nativeProductId, nativeMediaIds, currentAuthentication());
        List<ProductMedia> media = null;
        if (result.media() != null) {
            media = new ArrayList<>(result.media().size());
            for (ProductMediaView view : result.media()) {
                media.add(toGraphQlProductMedia(view));
            }
        }
        return ProductMediaReorder.newBuilder()
                .errors(toProductErrors(result.errors()))
                .media(media)
                .product(resolveProduct(result.productId()))
                .build();
    }

    /**
     * The caller's authentication — the owning provider resolves from
     * the security context (the {@code ProductVariantMutationDataFetcher}
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
     * The payload's {@code product} projection — the catalog owner's
     * read seam over the result's owning-product carrier (the read's
     * own visibility law applies: an unreadable row answers the null
     * projection, never an error).
     */
    private com.marketplace.app.graphql.generated.types.Product resolveProduct(UUID productId) {
        if (productId == null) {
            return null;
        }
        return productReadSpi.findProductById(productId, currentAuthentication())
                .map(productMapper::toGraphQlProduct)
                .orElse(null);
    }

    /**
     * The media owner's bounded projection onto the Saleor type's own
     * declared closure — the typed global IDs (the row's own tag, the
     * owning product's own tag) and the display sort order.
     */
    private ProductMedia toGraphQlProductMedia(ProductMediaView view) {
        return ProductMedia.newBuilder()
                .id(globalIdCodec.encode(PRODUCT_MEDIA_TYPE, view.id()))
                .sortOrder(view.position())
                .productId(view.productId() == null
                        ? null : globalIdCodec.encode(ProductGraphQlMapper.PRODUCT_TYPE, view.productId()))
                .build();
    }

    /**
     * The domain error entries onto the Saleor payload error objects —
     * the registered ADAPT mapping (the codes are the shared
     * {@code ProductErrorCode} members the media family's bounded
     * vocabulary maps onto; the field/message ride verbatim).
     */
    private List<ProductError> toProductErrors(List<ProductMediaMutationError> errors) {
        List<ProductError> mapped = new ArrayList<>(errors.size());
        for (ProductMediaMutationError error : errors) {
            mapped.add(ProductError.newBuilder()
                    .field(error.field())
                    .message(error.message())
                    .code(toProductErrorCode(error.code()))
                    .build());
        }
        return mapped;
    }

    /** The domain code onto the Saleor enum's own member. */
    private static ProductErrorCode toProductErrorCode(ProductMediaMutationErrorCode code) {
        return switch (code) {
            case DUPLICATED_INPUT_ITEM -> ProductErrorCode.DUPLICATED_INPUT_ITEM;
            case INVALID -> ProductErrorCode.INVALID;
            case NOT_FOUND -> ProductErrorCode.NOT_FOUND;
            case REQUIRED -> ProductErrorCode.REQUIRED;
        };
    }
}
