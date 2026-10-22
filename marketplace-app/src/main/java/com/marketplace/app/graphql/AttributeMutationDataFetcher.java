package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.AttributeBulkCreate;
import com.marketplace.app.graphql.generated.types.AttributeBulkDelete;
import com.marketplace.app.graphql.generated.types.AttributeBulkCreateError;
import com.marketplace.app.graphql.generated.types.AttributeBulkCreateErrorCode;

import com.marketplace.app.graphql.generated.types.AttributeBulkCreateResult;
import com.marketplace.app.graphql.generated.types.AttributeBulkUpdate;
import com.marketplace.app.graphql.generated.types.AttributeBulkUpdateError;
import com.marketplace.app.graphql.generated.types.AttributeBulkUpdateErrorCode;
import com.marketplace.app.graphql.generated.types.AttributeBulkUpdateInput;
import com.marketplace.app.graphql.generated.types.AttributeBulkUpdateResult;
import com.marketplace.app.graphql.generated.types.AttributeCreate;
import com.marketplace.app.graphql.generated.types.AttributeCreateInput;
import com.marketplace.app.graphql.generated.types.AttributeError;
import com.marketplace.app.graphql.generated.types.AttributeErrorCode;
import com.marketplace.app.graphql.generated.types.AttributeReorderValues;
import com.marketplace.app.graphql.generated.types.AttributeUpdate;
import com.marketplace.app.graphql.generated.types.AttributeUpdateInput;
import com.marketplace.app.graphql.generated.types.ErrorPolicyEnum;
import com.marketplace.app.graphql.generated.types.ReorderInput;
import com.marketplace.catalog.CatalogAttributeType;
import com.marketplace.catalog.spi.AttributeBulkOutcome;
import com.marketplace.catalog.spi.AttributeBulkUpdateItem;
import com.marketplace.catalog.spi.AttributeCreateCommand;
import com.marketplace.catalog.spi.AttributeMutationError;
import com.marketplace.catalog.spi.AttributeMutationErrorCode;
import com.marketplace.catalog.spi.AttributeMutationResult;
import com.marketplace.catalog.spi.AttributeReorderMove;
import com.marketplace.catalog.spi.AttributeUpdateCommand;
import com.marketplace.catalog.spi.AttributeWriteSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2): the GraphQL adapter for the
 * Saleor Attributes-domain {@code attributeCreate},
 * {@code attributeUpdate}, {@code attributeDelete} and
 * {@code attributeBulkDelete} mutation roots — over the catalog
 * owner's write use cases through the declared
 * {@code AttributeWriteSpi} seam. This class owns ONLY transport
 * mapping: typed global-ID decode, the bounded input-to-command
 * mapping (the Saleor {@code inputType} widget vocabulary onto the
 * closed domain value shape — the read mapper's own inverse) and the
 * domain result onto Saleor's payload shapes. Authorization lives in
 * the owning use case ({@code @PreAuthorize("hasRole('ADMIN')")} —
 * the attribute family's platform capability), exactly as the
 * product-type registry's write use case established.
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
public class AttributeMutationDataFetcher {

    private final AttributeWriteSpi attributeWriteSpi;
    private final AttributeGraphQlMapper attributeMapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public AttributeMutationDataFetcher(AttributeWriteSpi attributeWriteSpi,
                                        AttributeGraphQlMapper attributeMapper,
                                        SaleorGlobalIdCodec globalIdCodec) {
        this.attributeWriteSpi = attributeWriteSpi;
        this.attributeMapper = attributeMapper;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code attributeCreate(input: AttributeCreateInput!):
     * AttributeCreate} — the typed attribute registration over the
     * owner's use case. The bounded input's own {@code name}/{@code slug}
     * bind the row's display/code columns and the {@code inputType} rides
     * the read mapper's own inverse onto the closed domain value shape.
     */
    @DgsMutation(field = DgsConstants.MUTATION.AttributeCreate)
    @RateLimiter(name = "graphqlAttributeMutation")
    public AttributeCreate attributeCreate(@InputArgument AttributeCreateInput input) {
        requireInput(input, "attributeCreate");
        AttributeMutationResult result = attributeWriteSpi.createAttribute(
                new com.marketplace.catalog.spi.AttributeCreateCommand(
                        input.getName(),
                        input.getSlug(),
                        AttributeGraphQlMapper.fromInputType(input.getInputType())));
        return AttributeCreate.newBuilder()
                .errors(toAttributeErrors(result.errors()))
                .attribute(result.attribute() == null
                        ? null
                        : attributeMapper.toGraphQlAttribute(result.attribute()))
                .build();
    }

    /**
     * Saleor {@code attributeUpdate(externalReference: String, id: ID,
     * input: AttributeUpdateInput!): AttributeUpdate} — the typed
     * attribute amendment. The {@code id} argument executes through the
     * typed global-ID codec (a malformed or mistyped ID answers the
     * classified VAL-001 family); the {@code externalReference} argument
     * has no owning column and answers the classified error when sent.
     */
    @DgsMutation(field = DgsConstants.MUTATION.AttributeUpdate)
    @RateLimiter(name = "graphqlAttributeMutation")
    public AttributeUpdate attributeUpdate(@InputArgument String externalReference,
                                           @InputArgument String id,
                                           @InputArgument AttributeUpdateInput input) {
        requireInput(input, "attributeUpdate");
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "attributeUpdate externalReference is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException("attributeUpdate requires the id of the attribute to update");
        }
        UUID nativeId = globalIdCodec.decode(id, AttributeGraphQlMapper.ATTRIBUTE_TYPE);
        AttributeMutationResult result = attributeWriteSpi.updateAttribute(
                nativeId, new AttributeUpdateCommand(input.getName(), input.getSlug()));
        return AttributeUpdate.newBuilder()
                .errors(toAttributeErrors(result.errors()))
                .attribute(result.attribute() == null
                        ? null
                        : attributeMapper.toGraphQlAttribute(result.attribute()))
                .build();
    }

    /**
     * Saleor {@code attributeDelete(externalReference: String, id: ID):
     * AttributeDelete} — the typed attribute soft delete. The
     * {@code id} argument executes through the typed global-ID codec; a
     * keyless call answers the classified validation error (never a
     * fabricated absence).
     */
    @DgsMutation(field = DgsConstants.MUTATION.AttributeDelete)
    @RateLimiter(name = "graphqlAttributeMutation")
    public com.marketplace.app.graphql.generated.types.AttributeDelete attributeDelete(
            @InputArgument String externalReference,
            @InputArgument String id) {
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "attributeDelete externalReference is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException("attributeDelete requires the id of the attribute to delete");
        }
        UUID nativeId = globalIdCodec.decode(id, AttributeGraphQlMapper.ATTRIBUTE_TYPE);
        AttributeMutationResult result = attributeWriteSpi.deleteAttribute(nativeId);
        return com.marketplace.app.graphql.generated.types.AttributeDelete.newBuilder()
                .errors(toAttributeErrors(result.errors()))
                .attribute(result.attribute() == null
                        ? null
                        : attributeMapper.toGraphQlAttribute(result.attribute()))
                .build();
    }

    /**
     * Saleor {@code attributeBulkDelete(ids: [ID!]!):
     * AttributeBulkDelete} — the typed attribute bulk soft delete.
     * Every id executes through the typed global-ID codec (one
     * malformed id fails the whole call — the classified VAL-001
     * family); unknown-but-well-formed ids are the owner's own honest
     * skip, and the count answers what the registry itself deleted.
     */
    @DgsMutation(field = DgsConstants.MUTATION.AttributeBulkDelete)
    @RateLimiter(name = "graphqlAttributeMutation")
    public AttributeBulkDelete attributeBulkDelete(@InputArgument List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("attributeBulkDelete requires a non-empty list of ids");
        }
        List<UUID> nativeIds = new ArrayList<>(ids.size());
        for (String id : ids) {
            nativeIds.add(globalIdCodec.decode(id, AttributeGraphQlMapper.ATTRIBUTE_TYPE));
        }
        return AttributeBulkDelete.newBuilder()
                .count(attributeWriteSpi.deleteAttributes(nativeIds))
                .errors(List.of())
                .build();
    }

    /**
     * Saleor {@code attributeReorderValues(attributeId: ID!, moves:
     * [ReorderInput!]!): AttributeReorderValues} — the attribute's
     * registered option values' reorder. The attribute reference and
     * every move's value reference execute through the typed
     * global-ID codec; the pinned relative moves ride the command
     * verbatim (the owning use case resolves them against the
     * registry's current order).
     */
    @DgsMutation(field = DgsConstants.MUTATION.AttributeReorderValues)
    @RateLimiter(name = "graphqlAttributeMutation")
    public AttributeReorderValues attributeReorderValues(@InputArgument String attributeId,
                                                          @InputArgument List<ReorderInput> moves) {
        if (attributeId == null) {
            throw new IllegalArgumentException(
                    "attributeReorderValues requires the attributeId of the values to reorder");
        }
        if (moves == null || moves.isEmpty()) {
            throw new IllegalArgumentException(
                    "attributeReorderValues requires a non-empty list of moves");
        }
        UUID nativeAttributeId = globalIdCodec.decode(attributeId, AttributeGraphQlMapper.ATTRIBUTE_TYPE);
        List<AttributeReorderMove> commands = new ArrayList<>(moves.size());
        for (ReorderInput move : moves) {
            commands.add(new AttributeReorderMove(
                    move.getId() == null ? null
                            : globalIdCodec.decode(move.getId(), AttributeGraphQlMapper.ATTRIBUTE_VALUE_TYPE),
                    move.getSortOrder()));
        }
        AttributeMutationResult result = attributeWriteSpi.reorderValues(nativeAttributeId, commands);
        return AttributeReorderValues.newBuilder()
                .errors(toAttributeErrors(result.errors()))
                .attribute(result.attribute() == null
                        ? null : attributeMapper.toGraphQlAttribute(result.attribute()))
                .build();
    }


    /**
     * Saleor {@code attributeBulkCreate(attributes:
     * [AttributeCreateInput!]!, errorPolicy: ErrorPolicyEnum):
     * AttributeBulkCreate} — the registry's bulk create. The bounded
     * input binds by name per item; the pinned policy rides the
     * command (null = the REJECT_EVERYTHING default); the per-item
     * domain outcomes render as Saleor's per-item results entries
     * (the {@code path} pointer carries the item's index + the domain
     * error's field).
     */
    @DgsMutation(field = DgsConstants.MUTATION.AttributeBulkCreate)
    @RateLimiter(name = "graphqlAttributeMutation")
    public AttributeBulkCreate attributeBulkCreate(
            @InputArgument List<AttributeCreateInput> attributes,
            @InputArgument ErrorPolicyEnum errorPolicy) {
        if (attributes == null || attributes.isEmpty()) {
            throw new IllegalArgumentException("attributeBulkCreate requires a non-empty list of attributes");
        }
        List<AttributeCreateCommand> commands = new ArrayList<>(attributes.size());
        for (AttributeCreateInput attribute : attributes) {
            commands.add(new AttributeCreateCommand(
                    attribute.getName(),
                    attribute.getSlug(),
                    attribute.getInputType() == null
                            ? null : AttributeGraphQlMapper.fromInputType(attribute.getInputType())));
        }
        List<AttributeBulkOutcome> outcomes = attributeWriteSpi.bulkCreateAttributes(
                commands, errorPolicy == null ? null : errorPolicy.name());
        List<AttributeBulkCreateResult> results = new ArrayList<>(outcomes.size());
        int count = 0;
        for (int i = 0; i < outcomes.size(); i++) {
            AttributeBulkOutcome outcome = outcomes.get(i);
            if (outcome.success()) {
                count++;
            }
            results.add(AttributeBulkCreateResult.newBuilder()
                    .attribute(outcome.attribute() == null
                            ? null : attributeMapper.toGraphQlAttribute(outcome.attribute()))
                    .errors(toBulkCreateErrors(outcome.errors(), "attributes." + i))
                    .build());
        }
        return AttributeBulkCreate.newBuilder()
                .count(count)
                .results(results)
                .errors(List.of())
                .build();
    }

    /**
     * Saleor {@code attributeBulkUpdate(attributes:
     * [AttributeBulkUpdateInput!]!, errorPolicy: ErrorPolicyEnum):
     * AttributeBulkUpdate} — the registry's bulk amendment over the
     * pinned {@code id + fields} items.
     */
    @DgsMutation(field = DgsConstants.MUTATION.AttributeBulkUpdate)
    @RateLimiter(name = "graphqlAttributeMutation")
    public AttributeBulkUpdate attributeBulkUpdate(
            @InputArgument List<AttributeBulkUpdateInput> attributes,
            @InputArgument ErrorPolicyEnum errorPolicy) {
        if (attributes == null || attributes.isEmpty()) {
            throw new IllegalArgumentException("attributeBulkUpdate requires a non-empty list of attributes");
        }
        List<AttributeBulkUpdateItem> items = new ArrayList<>(attributes.size());
        for (AttributeBulkUpdateInput attribute : attributes) {
            if (attribute.getId() == null) {
                throw new IllegalArgumentException(
                        "attributeBulkUpdate requires every item to carry the id of the attribute to update");
            }
            items.add(new AttributeBulkUpdateItem(
                    globalIdCodec.decode(attribute.getId(), AttributeGraphQlMapper.ATTRIBUTE_TYPE),
                    new AttributeUpdateCommand(attribute.getFields() == null ? null : attribute.getFields().getName(),
                            attribute.getFields() == null ? null : attribute.getFields().getSlug())));
        }
        List<AttributeBulkOutcome> outcomes = attributeWriteSpi.bulkUpdateAttributes(
                items, errorPolicy == null ? null : errorPolicy.name());
        List<AttributeBulkUpdateResult> results = new ArrayList<>(outcomes.size());
        int count = 0;
        for (int i = 0; i < outcomes.size(); i++) {
            AttributeBulkOutcome outcome = outcomes.get(i);
            if (outcome.success()) {
                count++;
            }
            results.add(AttributeBulkUpdateResult.newBuilder()
                    .attribute(outcome.attribute() == null
                            ? null : attributeMapper.toGraphQlAttribute(outcome.attribute()))
                    .errors(toBulkUpdateErrors(outcome.errors(), "attributes." + i))
                    .build());
        }
        return AttributeBulkUpdate.newBuilder()
                .count(count)
                .results(results)
                .errors(List.of())
                .build();
    }

    /**
     * The per-item domain error entries onto Saleor's bulk error objects
     * — the {@code path} pointer is the item's own index + the domain
     * error's field (the registered ADAPT mapping's own pointer shape).
     */
    private static List<AttributeBulkCreateError> toBulkCreateErrors(List<AttributeMutationError> errors,
                                                                     String itemPath) {
        List<AttributeBulkCreateError> mapped = new ArrayList<>(errors.size());
        for (AttributeMutationError error : errors) {
            mapped.add(AttributeBulkCreateError.newBuilder()
                    .path(error.field() == null ? itemPath : itemPath + "." + error.field())
                    .message(error.message())
                    .code(AttributeBulkCreateErrorCode.valueOf(toAttributeErrorCode(error.code()).name()))
                    .build());
        }
        return mapped;
    }

    private static List<AttributeBulkUpdateError> toBulkUpdateErrors(List<AttributeMutationError> errors,
                                                                     String itemPath) {
        List<AttributeBulkUpdateError> mapped = new ArrayList<>(errors.size());
        for (AttributeMutationError error : errors) {
            mapped.add(AttributeBulkUpdateError.newBuilder()
                    .path(error.field() == null ? itemPath : itemPath + "." + error.field())
                    .message(error.message())
                    .code(AttributeBulkUpdateErrorCode.valueOf(toAttributeErrorCode(error.code()).name()))
                    .build());
        }
        return mapped;
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
