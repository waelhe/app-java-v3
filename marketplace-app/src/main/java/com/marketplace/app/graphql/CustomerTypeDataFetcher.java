package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.CustomerType;
import com.marketplace.app.graphql.generated.types.CustomerTypeAssignAttributes;
import com.marketplace.app.graphql.generated.types.CustomerTypeCountableConnection;
import com.marketplace.app.graphql.generated.types.CustomerTypeCreate;
import com.marketplace.app.graphql.generated.types.CustomerTypeCreateInput;
import com.marketplace.app.graphql.generated.types.CustomerTypeDelete;
import com.marketplace.app.graphql.generated.types.CustomerTypeReorderAttributes;
import com.marketplace.app.graphql.generated.types.CustomerTypeSortingInput;
import com.marketplace.app.graphql.generated.types.CustomerTypeUnassignAttributes;
import com.marketplace.app.graphql.generated.types.CustomerTypeUpdate;
import com.marketplace.app.graphql.generated.types.CustomerTypeUpdateInput;
import com.marketplace.app.graphql.generated.types.CustomerTypeWhereInput;
import com.marketplace.app.graphql.generated.types.ReorderInput;
import com.marketplace.identity.spi.CustomerTypeSpi;
import com.marketplace.identity.spi.CustomerTypeView;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * GraphQL adapter for the customer-type registry — the six
 * {@code customerType*} mutations (with the attribute
 * assign/unassign/reorder legs whose attribute IDs the composition
 * boundary validates through the catalog module's declared read SPI) and
 * the {@code customerType}/{@code customerTypes} read roots.
 * Transport-only (the frozen §7 contract): the registry's invariants and
 * the ADMIN enforcement live in the owning use case.
 */
@DgsComponent
public class CustomerTypeDataFetcher {

    private final CustomerTypeSpi customerTypeSpi;
    private final com.marketplace.catalog.spi.AttributeReadSpi attributeReadSpi;
    private final AgentOneGraphQlMappers mappers;
    private final SaleorGlobalIdCodec globalIdCodec;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final GraphQlProperties properties;

    public CustomerTypeDataFetcher(CustomerTypeSpi customerTypeSpi,
                                   com.marketplace.catalog.spi.AttributeReadSpi attributeReadSpi,
                                   AgentOneGraphQlMappers mappers,
                                   SaleorGlobalIdCodec globalIdCodec,
                                   EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                   GraphQlProperties properties) {
        this.customerTypeSpi = customerTypeSpi;
        this.attributeReadSpi = attributeReadSpi;
        this.mappers = mappers;
        this.globalIdCodec = globalIdCodec;
        this.cursorStrategy = cursorStrategy;
        this.properties = properties;
    }

    // ---------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------

    @DgsMutation(field = DgsConstants.MUTATION.CustomerTypeCreate)
    @RateLimiter(name = "graphqlAccountMutation")
    public CustomerTypeCreate customerTypeCreate(
            @InputArgument CustomerTypeCreateInput input) {
        if (input == null || input.getName() == null || input.getName().isBlank()) {
            throw new IllegalArgumentException("A customer type name is required");
        }
        return CustomerTypeCreate.newBuilder()
                .customerType(mappers.toGraphQlCustomerType(customerTypeSpi.create(
                        currentAuthentication(), input.getName(), input.getSlug(),
                        input.getIsDefault())))
                .errors(List.of())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.CustomerTypeUpdate)
    @RateLimiter(name = "graphqlAccountMutation")
    public CustomerTypeUpdate customerTypeUpdate(@InputArgument String id,
                                                 @InputArgument CustomerTypeUpdateInput input) {
        return CustomerTypeUpdate.newBuilder()
                .customerType(mappers.toGraphQlCustomerType(customerTypeSpi.update(
                        currentAuthentication(), decodeTypeId(id),
                        input == null ? null : input.getName(),
                        input == null ? null : input.getSlug(),
                        input == null ? null : input.getIsDefault())))
                .errors(List.of())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.CustomerTypeDelete)
    @RateLimiter(name = "graphqlAccountMutation")
    public CustomerTypeDelete customerTypeDelete(@InputArgument String id) {
        return CustomerTypeDelete.newBuilder()
                .customerType(mappers.toGraphQlCustomerType(
                        customerTypeSpi.delete(currentAuthentication(), decodeTypeId(id))))
                .errors(List.of())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.CustomerTypeAssignAttributes)
    @RateLimiter(name = "graphqlAccountMutation")
    public CustomerTypeAssignAttributes customerTypeAssignAttributes(
            @InputArgument List<String> attributeIds,
            @InputArgument String customerTypeId) {
        List<UUID> attributes = validatedAttributes(attributeIds);
        return CustomerTypeAssignAttributes.newBuilder()
                .customerType(mappers.toGraphQlCustomerType(
                        customerTypeSpi.assignAttributes(currentAuthentication(),
                                decodeTypeId(customerTypeId), attributes)))
                .errors(List.of())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.CustomerTypeUnassignAttributes)
    @RateLimiter(name = "graphqlAccountMutation")
    public CustomerTypeUnassignAttributes customerTypeUnassignAttributes(
            @InputArgument List<String> attributeIds,
            @InputArgument String customerTypeId) {
        List<UUID> attributes = validatedAttributes(attributeIds);
        return CustomerTypeUnassignAttributes.newBuilder()
                .customerType(mappers.toGraphQlCustomerType(
                        customerTypeSpi.unassignAttributes(currentAuthentication(),
                                decodeTypeId(customerTypeId), attributes)))
                .errors(List.of())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.CustomerTypeReorderAttributes)
    @RateLimiter(name = "graphqlAccountMutation")
    public CustomerTypeReorderAttributes customerTypeReorderAttributes(
            @InputArgument String customerTypeId,
            @InputArgument List<ReorderInput> moves) {
        if (moves == null || moves.isEmpty()) {
            throw new IllegalArgumentException("A list of reorder moves is required");
        }
        List<UUID> attributeIds = new ArrayList<>(moves.size());
        List<Integer> deltas = new ArrayList<>(moves.size());
        for (ReorderInput move : moves) {
            attributeIds.add(globalIdCodec.decode(move.getId(), "Attribute"));
            deltas.add(move.getSortOrder());
        }
        return CustomerTypeReorderAttributes.newBuilder()
                .customerType(mappers.toGraphQlCustomerType(
                        customerTypeSpi.reorderAttributes(currentAuthentication(),
                                decodeTypeId(customerTypeId), attributeIds, deltas)))
                .errors(List.of())
                .build();
    }

    // ---------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------

    @DgsQuery(field = DgsConstants.QUERY.CustomerType)
    @RateLimiter(name = "graphqlUserQuery")
    public CustomerType customerType(@InputArgument String id) {
        return customerTypeSpi.find(currentAuthentication(), decodeTypeId(id))
                .map(mappers::toGraphQlCustomerType)
                .orElse(null);
    }

    @DgsQuery(field = DgsConstants.QUERY.CustomerTypes)
    @RateLimiter(name = "graphqlUserQuery")
    public CustomerTypeCountableConnection customerTypes(
            @InputArgument Integer first,
            @InputArgument String after,
            @InputArgument String before,
            @InputArgument Integer last,
            @InputArgument String search,
            @InputArgument CustomerTypeSortingInput sortBy,
            @InputArgument CustomerTypeWhereInput where) {
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "customerTypes supports forward-only pagination (first/after) on this platform");
        }
        if (where != null && (where.getIds() != null || where.getIsDefault() != null
                || where.getName() != null || where.getSlug() != null)) {
            throw new IllegalArgumentException(
                    "customerTypes where filter is not implemented on this platform yet");
        }
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            return CustomerTypeCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(com.marketplace.app.graphql.generated.types.PageInfo.newBuilder()
                            .hasNextPage(false).hasPreviousPage(false).build())
                    .build();
        }
        CustomerTypeSpi.CustomerTypeSort sort = toSort(sortBy);
        KeysetScrollPosition position = after == null
                ? org.springframework.data.domain.ScrollPosition.keyset()
                : KeysetCursorGate.forwardKeyset(cursorStrategy, after,
                        sortProperty(sort), sortValueFunction(sort),
                        "customerTypes with the same sortBy");
        CustomerTypeSpi.CustomerTypePage page = customerTypeSpi.scroll(currentAuthentication(),
                sort, position, Limit.of(Math.min(requested, properties.pagination().maxPageSize())),
                search);
        return mappers.toCustomerTypeConnection(page.window(), page.total());
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /**
     * The attribute-ID validation at the composition boundary — every ID
     * decodes as a typed Attribute global ID and EXISTS in the catalog
     * registry (the declared read SPI), before the owner writes anything.
     */
    private List<UUID> validatedAttributes(List<String> attributeIds) {
        if (attributeIds == null || attributeIds.isEmpty() || attributeIds.size() > 100) {
            throw new IllegalArgumentException(
                    "Between one and a hundred attribute IDs are required");
        }
        Authentication caller = currentAuthentication();
        List<UUID> decoded = attributeIds.stream()
                .map(id -> globalIdCodec.decode(id, "Attribute"))
                .toList();
        for (UUID attributeId : decoded) {
            if (attributeReadSpi.findAttributeById(attributeId, caller).isEmpty()) {
                throw new com.marketplace.shared.api.ResourceNotFoundException(
                        "No attribute " + attributeId);
            }
        }
        return decoded;
    }

    private CustomerTypeSpi.CustomerTypeSort toSort(CustomerTypeSortingInput sortBy) {
        boolean ascending = sortBy == null || sortBy.getDirection() == null
                || com.marketplace.app.graphql.generated.types.OrderDirection.ASC
                        .equals(sortBy.getDirection());
        var field = sortBy == null || sortBy.getField() == null
                ? com.marketplace.app.graphql.generated.types.CustomerTypeSortField.NAME
                : sortBy.getField();
        return new CustomerTypeSpi.CustomerTypeSort(
                CustomerTypeSpi.CustomerTypeSort.Field.valueOf(field.name()), ascending);
    }

    private static String sortProperty(CustomerTypeSpi.CustomerTypeSort sort) {
        return switch (sort.field()) {
            case NAME -> "name";
            case SLUG -> "slug";
        };
    }

    private static java.util.function.Function<Object, Object> sortValueFunction(
            CustomerTypeSpi.CustomerTypeSort sort) {
        return switch (sort.field()) {
            case NAME, SLUG -> KeysetCursorGate::textValue;
        };
    }

    private UUID decodeTypeId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A customer type ID is required");
        }
        return globalIdCodec.decode(id, AgentOneGraphQlMappers.CUSTOMER_TYPE_TYPE);
    }

    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.authentication
                    .AuthenticationCredentialsNotFoundException(
                    "Authentication is required for customer type administration");
        }
        return authentication;
    }
}
