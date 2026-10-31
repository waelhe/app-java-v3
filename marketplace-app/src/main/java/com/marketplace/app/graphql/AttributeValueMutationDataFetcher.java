package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.AttributeError;
import com.marketplace.app.graphql.generated.types.AttributeErrorCode;
import com.marketplace.app.graphql.generated.types.AttributeValueBulkDelete;
import com.marketplace.app.graphql.generated.types.AttributeValueCreate;
import com.marketplace.app.graphql.generated.types.AttributeValueCreateInput;
import com.marketplace.app.graphql.generated.types.AttributeValueDelete;
import com.marketplace.app.graphql.generated.types.AttributeValueUpdate;
import com.marketplace.app.graphql.generated.types.AttributeValueUpdateInput;
import com.marketplace.catalog.spi.AttributeMutationError;
import com.marketplace.catalog.spi.AttributeMutationErrorCode;
import com.marketplace.catalog.spi.AttributeValueMutationResult;
import com.marketplace.catalog.spi.AttributeValueUpdateCommand;
import com.marketplace.catalog.spi.AttributeValueWriteSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2): the GraphQL adapter for the
 * Saleor Attributes-domain {@code attributeValueCreate},
 * {@code attributeValueUpdate}, {@code attributeValueDelete} and
 * {@code attributeValueBulkDelete} mutation roots — over the catalog
 * owner's write use cases through the declared
 * {@code AttributeValueWriteSpi} seam. This class owns ONLY transport
 * mapping: typed global-ID decode, the bounded input-to-command
 * mapping and the domain result onto Saleor's dual-projection payload
 * shapes (the value AND its owning attribute). Authorization lives in
 * the owning use case ({@code @PreAuthorize("hasRole('ADMIN')")} —
 * the attribute family's platform capability), exactly as the
 * attribute-definition write use case established.
 *
 * <p><b>The payload-error contract is the registered "Mutation payload
 * errors" ADAPT divergence:</b> domain-level validation outcomes ride
 * the payload's own {@code errors} field (mapped from the owner's
 * {@code AttributeMutationError} entries onto the Saleor
 * {@code AttributeError} vocabulary); transport-level validation — a
 * malformed or mistyped global ID, a keyless call — answers the
 * classified VAL-001 GraphQL error family the read roots established,
 * and the declared-but-unowned {@code externalReference} argument (no
 * external-ID column exists on this platform — the recorded
 * divergence) answers the explicit classified error when sent, never
 * a silent no-op.</p>
 */
@DgsComponent
public class AttributeValueMutationDataFetcher {

    private final AttributeValueWriteSpi attributeValueWriteSpi;
    private final AttributeGraphQlMapper attributeMapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public AttributeValueMutationDataFetcher(AttributeValueWriteSpi attributeValueWriteSpi,
                                             AttributeGraphQlMapper attributeMapper,
                                             SaleorGlobalIdCodec globalIdCodec) {
        this.attributeValueWriteSpi = attributeValueWriteSpi;
        this.attributeMapper = attributeMapper;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code attributeValueCreate(attribute: ID!,
     * input: AttributeValueCreateInput!): AttributeValueCreate} — the
     * option registration under its owning CHOICE attribute. The
     * {@code attribute} argument executes through the typed global-ID
     * codec (a malformed or mistyped ID answers the classified VAL-001
     * family).
     */
    @DgsMutation(field = DgsConstants.MUTATION.AttributeValueCreate)
    @RateLimiter(name = "graphqlAttributeValueMutation")
    public AttributeValueCreate attributeValueCreate(@InputArgument String attribute,
                                                     @InputArgument AttributeValueCreateInput input) {
        requireInput(input, "attributeValueCreate");
        if (attribute == null) {
            throw new IllegalArgumentException(
                    "attributeValueCreate requires the id of the owning attribute");
        }
        UUID nativeAttributeId = globalIdCodec.decode(attribute, AttributeGraphQlMapper.ATTRIBUTE_TYPE);
        AttributeValueMutationResult result = attributeValueWriteSpi.createAttributeValue(
                nativeAttributeId, new com.marketplace.catalog.spi.AttributeValueCreateCommand(
                        input.getName(), input.getSlug()));
        return AttributeValueCreate.newBuilder()
                .errors(toAttributeErrors(result.errors()))
                .attribute(result.attribute() == null
                        ? null
                        : attributeMapper.toGraphQlAttribute(result.attribute()))
                .attributeValue(result.value() == null
                        ? null
                        : attributeMapper.toGraphQlAttributeValue(result.value()))
                .build();
    }

    /**
     * Saleor {@code attributeValueUpdate(externalReference: String,
     * id: ID, input: AttributeValueUpdateInput!):
     * AttributeValueUpdate} — the option amendment. The {@code id}
     * argument executes through the typed global-ID codec; the
     * {@code externalReference} argument has no owning column and
     * answers the classified error when sent.
     */
    @DgsMutation(field = DgsConstants.MUTATION.AttributeValueUpdate)
    @RateLimiter(name = "graphqlAttributeValueMutation")
    public AttributeValueUpdate attributeValueUpdate(@InputArgument String externalReference,
                                                     @InputArgument String id,
                                                     @InputArgument AttributeValueUpdateInput input) {
        requireInput(input, "attributeValueUpdate");
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "attributeValueUpdate externalReference is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException(
                    "attributeValueUpdate requires the id of the attribute value to update");
        }
        UUID nativeId = globalIdCodec.decode(id, AttributeGraphQlMapper.ATTRIBUTE_VALUE_TYPE);
        AttributeValueMutationResult result = attributeValueWriteSpi.updateAttributeValue(
                nativeId, new AttributeValueUpdateCommand(input.getName(), input.getSlug()));
        return AttributeValueUpdate.newBuilder()
                .errors(toAttributeErrors(result.errors()))
                .attribute(result.attribute() == null
                        ? null
                        : attributeMapper.toGraphQlAttribute(result.attribute()))
                .attributeValue(result.value() == null
                        ? null
                        : attributeMapper.toGraphQlAttributeValue(result.value()))
                .build();
    }

    /**
     * Saleor {@code attributeValueDelete(externalReference: String,
     * id: ID): AttributeValueDelete} — the option soft delete. The
     * {@code id} argument executes through the typed global-ID codec; a
     * keyless call answers the classified validation error (never a
     * fabricated absence).
     */
    @DgsMutation(field = DgsConstants.MUTATION.AttributeValueDelete)
    @RateLimiter(name = "graphqlAttributeValueMutation")
    public AttributeValueDelete attributeValueDelete(@InputArgument String externalReference,
                                                     @InputArgument String id) {
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "attributeValueDelete externalReference is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException(
                    "attributeValueDelete requires the id of the attribute value to delete");
        }
        UUID nativeId = globalIdCodec.decode(id, AttributeGraphQlMapper.ATTRIBUTE_VALUE_TYPE);
        AttributeValueMutationResult result = attributeValueWriteSpi.deleteAttributeValue(nativeId);
        return AttributeValueDelete.newBuilder()
                .errors(toAttributeErrors(result.errors()))
                .attribute(result.attribute() == null
                        ? null
                        : attributeMapper.toGraphQlAttribute(result.attribute()))
                .attributeValue(result.value() == null
                        ? null
                        : attributeMapper.toGraphQlAttributeValue(result.value()))
                .build();
    }

    /**
     * Saleor {@code attributeValueBulkDelete(ids: [ID!]!):
     * AttributeValueBulkDelete} — the option bulk soft delete. Every id
     * executes through the typed global-ID codec (one malformed id
     * fails the whole call — the classified VAL-001 family);
     * unknown-but-well-formed ids are the owner's own honest skip, and
     * the count answers what the registry itself deleted.
     */
    @DgsMutation(field = DgsConstants.MUTATION.AttributeValueBulkDelete)
    @RateLimiter(name = "graphqlAttributeValueMutation")
    public AttributeValueBulkDelete attributeValueBulkDelete(@InputArgument List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("attributeValueBulkDelete requires a non-empty list of ids");
        }
        List<UUID> nativeIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            nativeIds.add(globalIdCodec.decode(id, AttributeGraphQlMapper.ATTRIBUTE_VALUE_TYPE));
        }
        return AttributeValueBulkDelete.newBuilder()
                .count(attributeValueWriteSpi.deleteAttributeValues(nativeIds))
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
    private static List<AttributeError> toAttributeErrors(List<AttributeMutationError> errors) {
        List<AttributeError> mapped = new ArrayList<>(errors.size());
        for (AttributeMutationError error : errors) {
            mapped.add(AttributeError.newBuilder()
                    .field(error.field())
                    .message(error.message())
                    .code(toAttributeErrorCode(error.code()))
                    .build());
        }
        return mapped;
    }

    /** The domain code onto the Saleor enum's own member. */
    private static AttributeErrorCode toAttributeErrorCode(AttributeMutationErrorCode code) {
        return switch (code) {
            case REQUIRED -> AttributeErrorCode.REQUIRED;
            case INVALID -> AttributeErrorCode.INVALID;
            case NOT_FOUND -> AttributeErrorCode.NOT_FOUND;
            case ALREADY_EXISTS -> AttributeErrorCode.ALREADY_EXISTS;
        };
    }
}
