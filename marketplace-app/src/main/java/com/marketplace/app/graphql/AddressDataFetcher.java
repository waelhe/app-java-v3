package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.AccountAddressCreate;
import com.marketplace.app.graphql.generated.types.AccountAddressDelete;
import com.marketplace.app.graphql.generated.types.AccountAddressUpdate;
import com.marketplace.app.graphql.generated.types.AccountSetDefaultAddress;
import com.marketplace.app.graphql.generated.types.AddressCreate;
import com.marketplace.app.graphql.generated.types.AddressDelete;
import com.marketplace.app.graphql.generated.types.AddressInput;
import com.marketplace.app.graphql.generated.types.AddressSetDefault;
import com.marketplace.app.graphql.generated.types.AddressTypeEnum;
import com.marketplace.app.graphql.generated.types.AddressUpdate;
import com.marketplace.identity.spi.AddressFields;
import com.marketplace.identity.spi.AddressManagementSpi;
import com.marketplace.identity.spi.AddressView;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696 — the
 * D-ADDR-001 adopted owner): the GraphQL adapter for the Saleor
 * Users-domain address lifecycle — the self-service
 * {@code accountAddress*}/{@code accountSetDefaultAddress} legs, the
 * staff-managed {@code address*} legs, the {@code address} read root and
 * the {@code User.addresses/defaultBilling/defaultShipping} read-back.
 * Transport-only (the frozen §7 contract): the IDOR wall and the
 * ADMIN/MANAGE_USERS enforcement live in the owning use case.
 */
@DgsComponent
public class AddressDataFetcher {

    private final AddressManagementSpi addressSpi;
    private final SaleorGlobalIdCodec globalIdCodec;
    private final AgentOneGraphQlMappers mappers;

    public AddressDataFetcher(AddressManagementSpi addressSpi,
                              SaleorGlobalIdCodec globalIdCodec,
                              AgentOneGraphQlMappers mappers) {
        this.addressSpi = addressSpi;
        this.globalIdCodec = globalIdCodec;
        this.mappers = mappers;
    }

    // ---------------------------------------------------------------------
    // Self-service legs
    // ---------------------------------------------------------------------

    @DgsMutation(field = DgsConstants.MUTATION.AccountAddressCreate)
    @RateLimiter(name = "graphqlAccountMutation")
    public AccountAddressCreate accountAddressCreate(@InputArgument String customerId,
                                                     @InputArgument AddressInput input,
                                                     @InputArgument AddressTypeEnum type) {
        rejectImpersonation(customerId);
        AddressView created = addressSpi.createForCaller(currentAuthentication(),
                toFields(input), type == null ? null : type.name());
        return AccountAddressCreate.newBuilder()
                .address(mappers.toGraphQlAddress(created))
                .user(null)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.AccountAddressUpdate)
    @RateLimiter(name = "graphqlAccountMutation")
    public AccountAddressUpdate accountAddressUpdate(@InputArgument String id,
                                                     @InputArgument AddressInput input) {
        AddressView updated = addressSpi.updateForCaller(currentAuthentication(),
                decodeAddressId(id), toFields(input));
        return AccountAddressUpdate.newBuilder()
                .address(mappers.toGraphQlAddress(updated))
                .user(null)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.AccountAddressDelete)
    @RateLimiter(name = "graphqlAccountMutation")
    public AccountAddressDelete accountAddressDelete(@InputArgument String id) {
        AddressView deleted = addressSpi.deleteForCaller(currentAuthentication(),
                decodeAddressId(id));
        return AccountAddressDelete.newBuilder()
                .address(mappers.toGraphQlAddress(deleted))
                .user(null)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.AccountSetDefaultAddress)
    @RateLimiter(name = "graphqlAccountMutation")
    public AccountSetDefaultAddress accountSetDefaultAddress(@InputArgument String id,
                                                             @InputArgument AddressTypeEnum type) {
        if (type == null) {
            throw new IllegalArgumentException("An address type (BILLING or SHIPPING) is required");
        }
        addressSpi.setCallerDefault(currentAuthentication(), decodeAddressId(id), type.name());
        return AccountSetDefaultAddress.newBuilder()
                .user(null)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    // ---------------------------------------------------------------------
    // Staff-managed legs
    // ---------------------------------------------------------------------

    @DgsMutation(field = DgsConstants.MUTATION.AddressCreate)
    @RateLimiter(name = "graphqlAccountMutation")
    public AddressCreate addressCreate(@InputArgument AddressInput input,
                                       @InputArgument String userId) {
        AddressView created = addressSpi.createForUser(currentAuthentication(),
                decodeUserId(userId), toFields(input));
        return AddressCreate.newBuilder()
                .address(mappers.toGraphQlAddress(created))
                .user(null)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.AddressUpdate)
    @RateLimiter(name = "graphqlAccountMutation")
    public AddressUpdate addressUpdate(@InputArgument String id,
                                       @InputArgument AddressInput input) {
        AddressView updated = addressSpi.updateForUser(currentAuthentication(),
                decodeAddressId(id), toFields(input));
        return AddressUpdate.newBuilder()
                .address(mappers.toGraphQlAddress(updated))
                .user(null)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.AddressDelete)
    @RateLimiter(name = "graphqlAccountMutation")
    public AddressDelete addressDelete(@InputArgument String id) {
        AddressView deleted = addressSpi.deleteForUser(currentAuthentication(),
                decodeAddressId(id));
        return AddressDelete.newBuilder()
                .address(mappers.toGraphQlAddress(deleted))
                .user(null)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.AddressSetDefault)
    @RateLimiter(name = "graphqlAccountMutation")
    public AddressSetDefault addressSetDefault(@InputArgument String addressId,
                                               @InputArgument AddressTypeEnum type,
                                               @InputArgument String userId) {
        if (type == null) {
            throw new IllegalArgumentException("An address type (BILLING or SHIPPING) is required");
        }
        addressSpi.setUserDefault(currentAuthentication(), decodeAddressId(addressId),
                type.name(), decodeUserId(userId));
        return AddressSetDefault.newBuilder()
                .user(null)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    // ---------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------

    /** Saleor {@code address(id)} — the typed-global-ID lookup, ADMIN-gated at the owner. */
    @DgsQuery(field = DgsConstants.QUERY.Address)
    @RateLimiter(name = "graphqlUserQuery")
    public com.marketplace.app.graphql.generated.types.Address address(
            @InputArgument String id) {
        return addressSpi.find(currentAuthentication(), decodeAddressId(id))
                .map(mappers::toGraphQlAddress)
                .orElse(null);
    }

    /** The {@code User.addresses} read-back (the customer editor's list). */
    @DgsData(parentType = DgsConstants.USER.TYPE_NAME, field = DgsConstants.USER.Addresses)
    public List<com.marketplace.app.graphql.generated.types.Address> userAddresses(
            graphql.schema.DataFetchingEnvironment environment) {
        UUID userId = userIdOfSource(environment);
        if (userId == null) {
            return List.of();
        }
        return addressSpi.listByUser(userId).stream()
                .map(mappers::toGraphQlAddress)
                .toList();
    }

    /** The {@code User.defaultShippingAddress} read-back. */
    @DgsData(parentType = DgsConstants.USER.TYPE_NAME,
            field = DgsConstants.USER.DefaultShippingAddress)
    public com.marketplace.app.graphql.generated.types.Address defaultShippingAddress(
            graphql.schema.DataFetchingEnvironment environment) {
        UUID userId = userIdOfSource(environment);
        if (userId == null) {
            return null;
        }
        return addressSpi.findDefault(userId, "SHIPPING")
                .map(mappers::toGraphQlAddress)
                .orElse(null);
    }

    /** The {@code User.defaultBillingAddress} read-back. */
    @DgsData(parentType = DgsConstants.USER.TYPE_NAME,
            field = DgsConstants.USER.DefaultBillingAddress)
    public com.marketplace.app.graphql.generated.types.Address defaultBillingAddress(
            graphql.schema.DataFetchingEnvironment environment) {
        UUID userId = userIdOfSource(environment);
        if (userId == null) {
            return null;
        }
        return addressSpi.findDefault(userId, "BILLING")
                .map(mappers::toGraphQlAddress)
                .orElse(null);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private UUID userIdOfSource(graphql.schema.DataFetchingEnvironment environment) {
        Object source = environment.getSource();
        if (source instanceof com.marketplace.app.graphql.generated.types.User user
                && user.getId() != null) {
            return globalIdCodec.decode(user.getId(), "User");
        }
        return null;
    }

    private AddressFields toFields(AddressInput input) {
        if (input == null) {
            throw new IllegalArgumentException("An address input is required");
        }
        return new AddressFields(input.getFirstName(), input.getLastName(),
                input.getCompanyName(), input.getStreetAddress1(), input.getStreetAddress2(),
                input.getCity(), input.getCityArea(), input.getPostalCode(),
                input.getCountry() == null ? null : input.getCountry().name(),
                input.getCountryArea(), input.getPhone());
    }

    private UUID decodeAddressId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("An address ID is required");
        }
        return globalIdCodec.decode(id, AgentOneGraphQlMappers.ADDRESS_TYPE);
    }

    private UUID decodeUserId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A user ID is required");
        }
        return globalIdCodec.decode(id, "User");
    }

    private static void rejectImpersonation(String customerId) {
        if (customerId != null && !customerId.isBlank()) {
            throw new IllegalArgumentException(
                    "accountAddressCreate customerId (app impersonation) is not implemented "
                            + "on this platform yet");
        }
    }

    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.authentication
                    .AuthenticationCredentialsNotFoundException(
                    "Authentication is required for address operations");
        }
        return authentication;
    }
}
