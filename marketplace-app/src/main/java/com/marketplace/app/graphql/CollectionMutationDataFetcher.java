package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.CollectionBulkDelete;
import com.marketplace.app.graphql.generated.types.CollectionCreate;
import com.marketplace.app.graphql.generated.types.CollectionCreateInput;
import com.marketplace.app.graphql.generated.types.CollectionDelete;
import com.marketplace.app.graphql.generated.types.CollectionError;
import com.marketplace.app.graphql.generated.types.CollectionErrorCode;
import com.marketplace.app.graphql.generated.types.CollectionInput;
import com.marketplace.app.graphql.generated.types.CollectionUpdate;
import com.marketplace.app.graphql.generated.types.MoveProductInput;
import com.marketplace.catalog.spi.CollectionCreateCommand;
import com.marketplace.catalog.spi.CollectionMutationError;
import com.marketplace.catalog.spi.CollectionMutationErrorCode;
import com.marketplace.catalog.spi.CollectionMutationResult;
import com.marketplace.catalog.spi.CollectionProductMove;
import com.marketplace.catalog.spi.CollectionUpdateCommand;
import com.marketplace.catalog.spi.CollectionWriteSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * L-slice (the canonical Saleor plan §5-P2): the GraphQL adapter for the
 * Saleor Products-domain {@code collectionCreate}, {@code collectionUpdate},
 * {@code collectionDelete}, {@code collectionBulkDelete},
 * {@code collectionAddProducts}, {@code collectionRemoveProducts} and
 * {@code collectionReorderProducts} mutation roots, packet agent-2 — over
 * the catalog owner's write use cases through the declared
 * {@code CollectionWriteSpi} seam. This class owns ONLY transport mapping:
 * typed global-ID decode, the bounded input-to-command mapping and the
 * domain result onto Saleor's payload shapes. Authorization lives in the
 * owning use case ({@code @PreAuthorize("hasRole('ADMIN')")} — the
 * MANAGE_PRODUCTS root's platform capability), exactly as every write
 * family in this packet established.
 *
 * <p><b>The payload-error contract is the registered "Mutation payload
 * errors" ADAPT divergence:</b> domain-level validation outcomes ride the
 * payload's own {@code errors} field (mapped from the owner's
 * {@code CollectionMutationError} entries onto the
 * {@code CollectionError} vocabulary, including the pinned shape's own
 * {@code products} member for the product-addressed outcomes);
 * transport-level validation — a malformed or mistyped global ID, a
 * keyless call — answers the classified VAL-001 GraphQL error family the
 * read roots established, and a denied authorization answers Spring
 * Security's own 403 problem. Declared-but-unowned arguments
 * ({@code externalReference} — no external-ID column) answer the explicit
 * classified error when sent, never a silent no-op.</p>
 */
@DgsComponent
public class CollectionMutationDataFetcher {

    private final CollectionWriteSpi collectionWriteSpi;
    private final CollectionGraphQlMapper collectionMapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public CollectionMutationDataFetcher(CollectionWriteSpi collectionWriteSpi,
                                         CollectionGraphQlMapper collectionMapper,
                                         SaleorGlobalIdCodec globalIdCodec) {
        this.collectionWriteSpi = collectionWriteSpi;
        this.collectionMapper = collectionMapper;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code collectionCreate(input: CollectionCreateInput!):
     * CollectionCreate} — the registry write over the owner's use case.
     * The bounded input's owned field set (name, slug, isPublished, the
     * initial member {@code products} as typed global IDs) rides the
     * create command; the unowned members stay undeclared at the SDL
     * boundary.
     */
    @DgsMutation(field = DgsConstants.MUTATION.CollectionCreate)
    @RateLimiter(name = "graphqlCollectionMutation")
    public CollectionCreate collectionCreate(@InputArgument CollectionCreateInput input) {
        requireInput(input, "collectionCreate");
        CollectionMutationResult result = collectionWriteSpi.createCollection(
                new CollectionCreateCommand(input.getName(), input.getSlug(),
                        input.getIsPublished(), decodeProductIds(input.getProducts())));
        return CollectionCreate.newBuilder()
                .errors(toCollectionErrors(result.errors()))
                .collection(result.collection() == null
                        ? null : collectionMapper.toGraphQlCollection(result.collection()))
                .build();
    }

    /**
     * Saleor {@code collectionUpdate(externalReference: String, id: ID,
     * input: CollectionInput!): CollectionUpdate} — the registry amendment.
     * The {@code id} argument executes through the typed global-ID codec;
     * the {@code externalReference} argument has no owning column and
     * answers the classified error when sent.
     */
    @DgsMutation(field = DgsConstants.MUTATION.CollectionUpdate)
    @RateLimiter(name = "graphqlCollectionMutation")
    public CollectionUpdate collectionUpdate(@InputArgument String externalReference,
                                             @InputArgument String id,
                                             @InputArgument CollectionInput input) {
        requireInput(input, "collectionUpdate");
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "collectionUpdate externalReference is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException("collectionUpdate requires the id of the collection to update");
        }
        UUID nativeId = globalIdCodec.decode(id, CollectionGraphQlMapper.COLLECTION_TYPE);
        CollectionMutationResult result = collectionWriteSpi.updateCollection(
                nativeId, new CollectionUpdateCommand(input.getName(), input.getSlug(),
                        input.getIsPublished()));
        return CollectionUpdate.newBuilder()
                .errors(toCollectionErrors(result.errors()))
                .collection(result.collection() == null
                        ? null : collectionMapper.toGraphQlCollection(result.collection()))
                .build();
    }

    /**
     * Saleor {@code collectionDelete(externalReference: String, id: ID):
     * CollectionDelete} — the registry soft delete (the live membership
     * rides the owner's own transaction). The {@code id} argument executes
     * through the typed global-ID codec; a keyless call answers the
     * classified validation error.
     */
    @DgsMutation(field = DgsConstants.MUTATION.CollectionDelete)
    @RateLimiter(name = "graphqlCollectionMutation")
    public CollectionDelete collectionDelete(@InputArgument String externalReference,
                                             @InputArgument String id) {
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "collectionDelete externalReference is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException("collectionDelete requires the id of the collection to delete");
        }
        UUID nativeId = globalIdCodec.decode(id, CollectionGraphQlMapper.COLLECTION_TYPE);
        CollectionMutationResult result = collectionWriteSpi.deleteCollection(nativeId);
        return CollectionDelete.newBuilder()
                .errors(toCollectionErrors(result.errors()))
                .collection(result.collection() == null
                        ? null : collectionMapper.toGraphQlCollection(result.collection()))
                .build();
    }

    /**
     * Saleor {@code collectionBulkDelete(ids: [ID!]!): CollectionBulkDelete} —
     * the registry bulk soft delete. Every id executes through the typed
     * global-ID codec (one malformed id fails the whole call — the
     * classified VAL-001 family); unknown-but-well-formed ids are the
     * owner's own honest skip, and the count answers what the registry
     * itself deleted.
     */
    @DgsMutation(field = DgsConstants.MUTATION.CollectionBulkDelete)
    @RateLimiter(name = "graphqlCollectionMutation")
    public CollectionBulkDelete collectionBulkDelete(@InputArgument List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("collectionBulkDelete requires a non-empty list of ids");
        }
        List<UUID> nativeIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            nativeIds.add(globalIdCodec.decode(id, CollectionGraphQlMapper.COLLECTION_TYPE));
        }
        return CollectionBulkDelete.newBuilder()
                .count(collectionWriteSpi.deleteCollections(nativeIds))
                .errors(List.of())
                .build();
    }

    /**
     * Saleor {@code collectionAddProducts(collectionId: ID!, products:
     * [ID!]!): CollectionAddProducts} — the membership's append over the
     * owner's use case. Both arguments execute through the typed
     * global-ID codec (a malformed or mistyped product ID answers the
     * classified VAL-001 family — never a silent skip).
     */
    @DgsMutation(field = DgsConstants.MUTATION.CollectionAddProducts)
    @RateLimiter(name = "graphqlCollectionMutation")
    public com.marketplace.app.graphql.generated.types.CollectionAddProducts collectionAddProducts(
            @InputArgument String collectionId,
            @InputArgument List<String> products) {
        UUID nativeCollectionId = requireCollectionKey(collectionId, "collectionAddProducts");
        List<UUID> nativeProductIds = requireProductKeys(products, "collectionAddProducts");
        CollectionMutationResult result = collectionWriteSpi.addProducts(
                nativeCollectionId, nativeProductIds);
        return com.marketplace.app.graphql.generated.types.CollectionAddProducts.newBuilder()
                .errors(toCollectionErrors(result.errors()))
                .collection(result.collection() == null
                        ? null : collectionMapper.toGraphQlCollection(result.collection()))
                .build();
    }

    /**
     * Saleor {@code collectionRemoveProducts(collectionId: ID!, products:
     * [ID!]!): CollectionRemoveProducts} — the membership's remove over
     * the owner's use case (the idempotent skip is the owner's own law).
     */
    @DgsMutation(field = DgsConstants.MUTATION.CollectionRemoveProducts)
    @RateLimiter(name = "graphqlCollectionMutation")
    public com.marketplace.app.graphql.generated.types.CollectionRemoveProducts collectionRemoveProducts(
            @InputArgument String collectionId,
            @InputArgument List<String> products) {
        UUID nativeCollectionId = requireCollectionKey(collectionId, "collectionRemoveProducts");
        List<UUID> nativeProductIds = requireProductKeys(products, "collectionRemoveProducts");
        CollectionMutationResult result = collectionWriteSpi.removeProducts(
                nativeCollectionId, nativeProductIds);
        return com.marketplace.app.graphql.generated.types.CollectionRemoveProducts.newBuilder()
                .errors(toCollectionErrors(result.errors()))
                .collection(result.collection() == null
                        ? null : collectionMapper.toGraphQlCollection(result.collection()))
                .build();
    }

    /**
     * Saleor {@code collectionReorderProducts(collectionId: ID!, moves:
     * [MoveProductInput!]!): CollectionReorderProducts} — the membership's
     * reorder over the owner's use case (the pinned relative moves resolve
     * against the membership's current order — never a client-supplied
     * absolute position).
     */
    @DgsMutation(field = DgsConstants.MUTATION.CollectionReorderProducts)
    @RateLimiter(name = "graphqlCollectionMutation")
    public com.marketplace.app.graphql.generated.types.CollectionReorderProducts collectionReorderProducts(
            @InputArgument String collectionId,
            @InputArgument List<MoveProductInput> moves) {
        UUID nativeCollectionId = requireCollectionKey(collectionId, "collectionReorderProducts");
        if (moves == null || moves.isEmpty()) {
            throw new IllegalArgumentException(
                    "collectionReorderProducts requires a non-empty list of moves");
        }
        List<CollectionProductMove> nativeMoves = new ArrayList<>(moves.size());
        for (MoveProductInput move : moves) {
            if (move.getProductId() == null) {
                throw new IllegalArgumentException(
                        "collectionReorderProducts requires each move's productId");
            }
            nativeMoves.add(new CollectionProductMove(
                    globalIdCodec.decode(move.getProductId(),
                            ProductGraphQlMapper.PRODUCT_TYPE),
                    move.getSortOrder()));
        }
        CollectionMutationResult result = collectionWriteSpi.reorderProducts(
                nativeCollectionId, nativeMoves);
        return com.marketplace.app.graphql.generated.types.CollectionReorderProducts.newBuilder()
                .errors(toCollectionErrors(result.errors()))
                .collection(result.collection() == null
                        ? null : collectionMapper.toGraphQlCollection(result.collection()))
                .build();
    }

    /**
     * The collection key guard — the SDL's own {@code collectionId: ID!}
     * contract mirrored at the boundary (the typed global-ID decode rides
     * the codec's classified validation family).
     */
    private UUID requireCollectionKey(String collectionId, String rootName) {
        if (collectionId == null) {
            throw new IllegalArgumentException(rootName + " requires the collectionId");
        }
        return globalIdCodec.decode(collectionId, CollectionGraphQlMapper.COLLECTION_TYPE);
    }

    /**
     * The product keys guard — the SDL's own {@code products: [ID!]!}
     * contract mirrored at the boundary.
     */
    private List<UUID> requireProductKeys(List<String> products, String rootName) {
        if (products == null || products.isEmpty()) {
            throw new IllegalArgumentException(rootName + " requires a non-empty list of products");
        }
        List<UUID> nativeIds = new ArrayList<>(products.size());
        for (String productId : products) {
            nativeIds.add(globalIdCodec.decode(productId, ProductGraphQlMapper.PRODUCT_TYPE));
        }
        return nativeIds;
    }

    /**
     * The initial-membership decode — the create input's own
     * {@code products: [ID!]} member (null/empty means no members). Every
     * id executes through the typed global-ID codec (a malformed or
     * mistyped id answers the classified VAL-001 family).
     */
    private List<UUID> decodeProductIds(List<String> products) {
        if (products == null || products.isEmpty()) {
            return List.of();
        }
        List<UUID> nativeIds = new ArrayList<>(products.size());
        for (String productId : products) {
            nativeIds.add(globalIdCodec.decode(productId, ProductGraphQlMapper.PRODUCT_TYPE));
        }
        return nativeIds;
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
     * members; the field/message ride verbatim; the pinned
     * {@code products} member carries the offending typed global IDs).
     */
    private List<CollectionError> toCollectionErrors(List<CollectionMutationError> errors) {
        List<CollectionError> mapped = new ArrayList<>(errors.size());
        for (CollectionMutationError error : errors) {
            List<String> productIds = null;
            if (error.products() != null && !error.products().isEmpty()) {
                productIds = new ArrayList<>(error.products().size());
                for (UUID productId : error.products()) {
                    productIds.add(globalIdCodec.encode(
                            ProductGraphQlMapper.PRODUCT_TYPE, productId));
                }
            }
            mapped.add(CollectionError.newBuilder()
                    .field(error.field())
                    .message(error.message())
                    .code(toCollectionErrorCode(error.code()))
                    .products(productIds)
                    .build());
        }
        return mapped;
    }

    /** The domain code onto the Saleor enum's own member. */
    private static CollectionErrorCode toCollectionErrorCode(CollectionMutationErrorCode code) {
        return switch (code) {
            case DUPLICATED_INPUT_ITEM -> CollectionErrorCode.DUPLICATED_INPUT_ITEM;
            case GRAPHQL_ERROR -> CollectionErrorCode.GRAPHQL_ERROR;
            case INVALID -> CollectionErrorCode.INVALID;
            case NOT_FOUND -> CollectionErrorCode.NOT_FOUND;
            case REQUIRED -> CollectionErrorCode.REQUIRED;
            case UNIQUE -> CollectionErrorCode.UNIQUE;
        };
    }
}
