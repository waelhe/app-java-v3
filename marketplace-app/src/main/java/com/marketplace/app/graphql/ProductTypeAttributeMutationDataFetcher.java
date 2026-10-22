package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.ProductAttributeAssign;
import com.marketplace.app.graphql.generated.types.ProductAttributeAssignInput;
import com.marketplace.app.graphql.generated.types.ProductAttributeAssignmentUpdate;
import com.marketplace.app.graphql.generated.types.ProductAttributeAssignmentUpdateInput;
import com.marketplace.app.graphql.generated.types.ProductAttributeType;
import com.marketplace.app.graphql.generated.types.ProductAttributeUnassign;
import com.marketplace.app.graphql.generated.types.ProductError;
import com.marketplace.app.graphql.generated.types.ProductErrorCode;
import com.marketplace.app.graphql.generated.types.ProductType;
import com.marketplace.app.graphql.generated.types.ProductTypeReorderAttributes;
import com.marketplace.app.graphql.generated.types.ReorderInput;
import com.marketplace.catalog.spi.AttributeReorderMove;
import com.marketplace.catalog.spi.ProductTypeAttributeAssignOperation;
import com.marketplace.catalog.spi.ProductTypeAttributeAssignmentUpdateOperation;
import com.marketplace.catalog.spi.ProductTypeAttributeWriteSpi;
import com.marketplace.catalog.spi.ProductTypeMutationError;
import com.marketplace.catalog.spi.ProductTypeMutationErrorCode;
import com.marketplace.catalog.spi.ProductTypeMutationResult;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2): the GraphQL adapter for the
 * Saleor Products-domain {@code productAttributeAssign},
 * {@code productAttributeAssignmentUpdate},
 * {@code productAttributeUnassign} and
 * {@code productTypeReorderAttributes} mutation roots — over the
 * catalog owner's product-type attribute schema write use cases
 * through the declared {@code ProductTypeAttributeWriteSpi} seam.
 * This class owns ONLY transport mapping: typed global-ID decode
 * (the attribute references and the owning product type), the bounded
 * input-to-command mapping (the pinned type/variantSelection pair
 * onto the platform's own scope model) and the domain result onto
 * Saleor's payload shapes. Authorization lives in the owning use case
 * ({@code @PreAuthorize("hasRole('ADMIN')")} — the
 * MANAGE_PRODUCT_TYPES_AND_ATTRIBUTES capability), exactly as the
 * module's own REST surface established.
 *
 * <p><b>The payload-error contract is the registered "Mutation payload
 * errors" ADAPT divergence:</b> domain-level validation outcomes ride
 * the payload's own {@code errors} field (mapped from the owner's
 * {@code ProductTypeMutationError} entries onto the ProductError
 * vocabulary); transport-level validation — a malformed or mistyped
 * global ID, a keyless call — answers the classified VAL-001 GraphQL
 * error family the read roots established.</p>
 */
@DgsComponent
public class ProductTypeAttributeMutationDataFetcher {

    private final ProductTypeAttributeWriteSpi schemaWriteSpi;
    private final ProductTypeGraphQlMapper productTypeMapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public ProductTypeAttributeMutationDataFetcher(ProductTypeAttributeWriteSpi schemaWriteSpi,
                                                   ProductTypeGraphQlMapper productTypeMapper,
                                                   SaleorGlobalIdCodec globalIdCodec) {
        this.schemaWriteSpi = schemaWriteSpi;
        this.productTypeMapper = productTypeMapper;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code productAttributeAssign(operations:
     * [ProductAttributeAssignInput!]!, productTypeId: ID!):
     * ProductAttributeAssign} — the schema's bulk assign. Every
     * attribute reference executes through the typed global-ID codec;
     * the pinned type/variantSelection pair rides the command
     * verbatim (the owning use case derives the platform's own scope
     * model).
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductAttributeAssign)
    @RateLimiter(name = "graphqlProductTypeMutation")
    public ProductAttributeAssign productAttributeAssign(
            @InputArgument List<ProductAttributeAssignInput> operations,
            @InputArgument String productTypeId) {
        if (productTypeId == null) {
            throw new IllegalArgumentException(
                    "productAttributeAssign requires the productTypeId of the schema to amend");
        }
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException(
                    "productAttributeAssign requires a non-empty list of operations");
        }
        UUID type = globalIdCodec.decode(productTypeId, ProductGraphQlMapper.PRODUCT_TYPE_REFERENCE);
        List<ProductTypeAttributeAssignOperation> commands = new ArrayList<>(operations.size());
        for (ProductAttributeAssignInput operation : operations) {
            commands.add(new ProductTypeAttributeAssignOperation(
                    operation.getId() == null ? null
                            : globalIdCodec.decode(operation.getId(), AttributeGraphQlMapper.ATTRIBUTE_TYPE),
                    operation.getType() == null ? null : operation.getType().name(),
                    operation.getVariantSelection()));
        }
        ProductTypeMutationResult result = schemaWriteSpi.assignAttributes(type, commands);
        return ProductAttributeAssign.newBuilder()
                .errors(toProductErrors(result.errors()))
                .productType(result.productType() == null
                        ? null : productTypeMapper.toGraphQlProductType(result.productType()))
                .build();
    }

    /**
     * Saleor {@code productAttributeAssignmentUpdate(operations:
     * [ProductAttributeAssignmentUpdateInput!]!, productTypeId: ID!):
     * ProductAttributeAssignmentUpdate} — the assignments'
     * variant-selection membership amendment (the scope move between
     * VARIANT and VARIANT_SELECTION).
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductAttributeAssignmentUpdate)
    @RateLimiter(name = "graphqlProductTypeMutation")
    public ProductAttributeAssignmentUpdate productAttributeAssignmentUpdate(
            @InputArgument List<ProductAttributeAssignmentUpdateInput> operations,
            @InputArgument String productTypeId) {
        if (productTypeId == null) {
            throw new IllegalArgumentException(
                    "productAttributeAssignmentUpdate requires the productTypeId of the schema to amend");
        }
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException(
                    "productAttributeAssignmentUpdate requires a non-empty list of operations");
        }
        UUID type = globalIdCodec.decode(productTypeId, ProductGraphQlMapper.PRODUCT_TYPE_REFERENCE);
        List<ProductTypeAttributeAssignmentUpdateOperation> commands = new ArrayList<>(operations.size());
        for (ProductAttributeAssignmentUpdateInput operation : operations) {
            commands.add(new ProductTypeAttributeAssignmentUpdateOperation(
                    operation.getId() == null ? null
                            : globalIdCodec.decode(operation.getId(), AttributeGraphQlMapper.ATTRIBUTE_TYPE),
                    Boolean.TRUE.equals(operation.getVariantSelection())));
        }
        ProductTypeMutationResult result = schemaWriteSpi.updateAssignments(type, commands);
        return ProductAttributeAssignmentUpdate.newBuilder()
                .errors(toProductErrors(result.errors()))
                .productType(result.productType() == null
                        ? null : productTypeMapper.toGraphQlProductType(result.productType()))
                .build();
    }

    /**
     * Saleor {@code productAttributeUnassign(attributeIds: [ID!]!,
     * productTypeId: ID!): ProductAttributeUnassign} — the schema's
     * bulk unassign. Every attribute reference executes through the
     * typed global-ID codec; unknown ids are the owner's own honest
     * skip.
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductAttributeUnassign)
    @RateLimiter(name = "graphqlProductTypeMutation")
    public ProductAttributeUnassign productAttributeUnassign(
            @InputArgument List<String> attributeIds,
            @InputArgument String productTypeId) {
        if (productTypeId == null) {
            throw new IllegalArgumentException(
                    "productAttributeUnassign requires the productTypeId of the schema to amend");
        }
        if (attributeIds == null || attributeIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "productAttributeUnassign requires a non-empty list of attributeIds");
        }
        UUID type = globalIdCodec.decode(productTypeId, ProductGraphQlMapper.PRODUCT_TYPE_REFERENCE);
        List<UUID> attributes = new ArrayList<>(attributeIds.size());
        for (String attributeId : attributeIds) {
            attributes.add(globalIdCodec.decode(attributeId, AttributeGraphQlMapper.ATTRIBUTE_TYPE));
        }
        ProductTypeMutationResult result = schemaWriteSpi.unassignAttributes(type, attributes);
        return ProductAttributeUnassign.newBuilder()
                .errors(toProductErrors(result.errors()))
                .productType(result.productType() == null
                        ? null : productTypeMapper.toGraphQlProductType(result.productType()))
                .build();
    }

    /**
     * Saleor {@code productTypeReorderAttributes(moves:
     * [ReorderInput!]!, productTypeId: ID!, type: ProductAttributeType!):
     * ProductTypeReorderAttributes} — the schema bucket's reorder. The
     * moves' attribute references execute through the typed global-ID
     * codec; the pinned type member addresses the bucket (PRODUCT or
     * VARIANT — the VARIANT bucket holds both VARIANT-family scopes).
     */
    @DgsMutation(field = DgsConstants.MUTATION.ProductTypeReorderAttributes)
    @RateLimiter(name = "graphqlProductTypeMutation")
    public ProductTypeReorderAttributes productTypeReorderAttributes(
            @InputArgument List<ReorderInput> moves,
            @InputArgument String productTypeId,
            @InputArgument ProductAttributeType type) {
        if (productTypeId == null) {
            throw new IllegalArgumentException(
                    "productTypeReorderAttributes requires the productTypeId of the schema to reorder");
        }
        if (type == null) {
            throw new IllegalArgumentException(
                    "productTypeReorderAttributes requires the attribute type bucket to reorder");
        }
        if (moves == null || moves.isEmpty()) {
            throw new IllegalArgumentException(
                    "productTypeReorderAttributes requires a non-empty list of moves");
        }
        UUID nativeTypeId = globalIdCodec.decode(productTypeId, ProductGraphQlMapper.PRODUCT_TYPE_REFERENCE);
        List<AttributeReorderMove> commands = new ArrayList<>(moves.size());
        for (ReorderInput move : moves) {
            commands.add(new AttributeReorderMove(
                    move.getId() == null ? null
                            : globalIdCodec.decode(move.getId(), AttributeGraphQlMapper.ATTRIBUTE_TYPE),
                    move.getSortOrder()));
        }
        ProductTypeMutationResult result = schemaWriteSpi.reorderAttributes(
                nativeTypeId, type.name(), commands);
        return ProductTypeReorderAttributes.newBuilder()
                .errors(toProductErrors(result.errors()))
                .productType(result.productType() == null
                        ? null : productTypeMapper.toGraphQlProductType(result.productType()))
                .build();
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
