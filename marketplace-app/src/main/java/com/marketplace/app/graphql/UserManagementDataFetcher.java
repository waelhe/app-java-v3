package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.CustomerBulkDelete;
import com.marketplace.app.graphql.generated.types.CustomerBulkUpdate;
import com.marketplace.app.graphql.generated.types.CustomerBulkUpdateInput;
import com.marketplace.app.graphql.generated.types.CustomerCreate;
import com.marketplace.app.graphql.generated.types.CustomerDelete;
import com.marketplace.app.graphql.generated.types.CustomerInput;
import com.marketplace.app.graphql.generated.types.CustomerUpdate;
import com.marketplace.app.graphql.generated.types.ErrorPolicyEnum;
import com.marketplace.app.graphql.generated.types.StaffBulkDelete;
import com.marketplace.app.graphql.generated.types.StaffCreate;
import com.marketplace.app.graphql.generated.types.StaffCreateInput;
import com.marketplace.app.graphql.generated.types.StaffDelete;
import com.marketplace.app.graphql.generated.types.StaffNotificationRecipientCreate;
import com.marketplace.app.graphql.generated.types.StaffNotificationRecipientDelete;
import com.marketplace.app.graphql.generated.types.StaffNotificationRecipientInput;
import com.marketplace.app.graphql.generated.types.StaffNotificationRecipientUpdate;
import com.marketplace.app.graphql.generated.types.StaffUpdate;
import com.marketplace.app.graphql.generated.types.StaffUpdateInput;
import com.marketplace.app.graphql.generated.types.UserBulkSetActive;
import com.marketplace.app.graphql.generated.types.UserCreateInput;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.identity.spi.UserManagementSpi;
import com.marketplace.identity.spi.UserWriteFields;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * GraphQL adapter for the staff-managed Users-domain mutations — the
 * customer lifecycle ({@code customer*}), the staff lifecycle
 * ({@code staff*}), {@code userBulkSetActive} and the staff notification
 * recipient registry. Transport-only (the frozen §7 contract): the ADMIN
 * enforcement (MANAGE_USERS/MANAGE_STAFF) and the two-store identity
 * moves live in the owning use case.
 */
@DgsComponent
public class UserManagementDataFetcher {

    private final UserManagementSpi userManagementSpi;
    private final IdentitySpi identitySpi;
    private final AgentOneGraphQlMappers mappers;
    private final UserGraphQlMapper userMapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public UserManagementDataFetcher(UserManagementSpi userManagementSpi,
                                     IdentitySpi identitySpi,
                                     AgentOneGraphQlMappers mappers,
                                     UserGraphQlMapper userMapper,
                                     SaleorGlobalIdCodec globalIdCodec) {
        this.userManagementSpi = userManagementSpi;
        this.identitySpi = identitySpi;
        this.mappers = mappers;
        this.userMapper = userMapper;
        this.globalIdCodec = globalIdCodec;
    }

    // ---------------------------------------------------------------------
    // Customers
    // ---------------------------------------------------------------------

    @DgsMutation(field = DgsConstants.MUTATION.CustomerCreate)
    @RateLimiter(name = "graphqlAccountMutation")
    public CustomerCreate customerCreate(@InputArgument UserCreateInput input) {
        if (input == null || input.getEmail() == null || input.getEmail().isBlank()) {
            throw new IllegalArgumentException("A customer email is required");
        }
        UUID userId = userManagementSpi.createCustomer(currentAuthentication(),
                toFields(input), input.getRedirectUrl() != null);
        return CustomerCreate.newBuilder()
                .user(userById(userId))
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.CustomerUpdate)
    @RateLimiter(name = "graphqlAccountMutation")
    public CustomerUpdate customerUpdate(@InputArgument String externalReference,
                                         @InputArgument String id,
                                         @InputArgument CustomerInput input) {
        UUID target = resolveTarget(externalReference, id);
        userManagementSpi.updateCustomer(currentAuthentication(), target, toFields(input));
        return CustomerUpdate.newBuilder()
                .user(userById(target))
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.CustomerDelete)
    @RateLimiter(name = "graphqlAccountMutation")
    public CustomerDelete customerDelete(@InputArgument String externalReference,
                                         @InputArgument String id) {
        UUID target = resolveTarget(externalReference, id);
        userManagementSpi.deleteCustomer(currentAuthentication(), target);
        return CustomerDelete.newBuilder()
                .user(null)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.CustomerBulkDelete)
    @RateLimiter(name = "graphqlAccountMutation")
    public CustomerBulkDelete customerBulkDelete(@InputArgument List<String> ids) {
        List<UUID> targets = decodeIds(ids, "A list of customer IDs is required");
        int count = userManagementSpi.bulkDeleteCustomers(currentAuthentication(), targets);
        return CustomerBulkDelete.newBuilder()
                .count(count)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.CustomerBulkUpdate)
    @RateLimiter(name = "graphqlAccountMutation")
    public CustomerBulkUpdate customerBulkUpdate(
            @InputArgument List<CustomerBulkUpdateInput> customers,
            @InputArgument ErrorPolicyEnum errorPolicy) {
        if (customers == null || customers.isEmpty()) {
            throw new IllegalArgumentException("A list of customer updates is required");
        }
        boolean ignoreFailed = errorPolicy == ErrorPolicyEnum.IGNORE_FAILED;
        List<UUID> ids = new ArrayList<>(customers.size());
        List<UserWriteFields> rows = new ArrayList<>(customers.size());
        for (CustomerBulkUpdateInput row : customers) {
            ids.add(resolveTarget(row.getExternalReference(), row.getId()));
            rows.add(toFields(row.getInput()));
        }
        List<com.marketplace.identity.spi.BulkRowResult> results =
                userManagementSpi.bulkUpdateCustomers(currentAuthentication(), ids, rows,
                        ignoreFailed);
        List<com.marketplace.app.graphql.generated.types.CustomerBulkResult> wireResults =
                new ArrayList<>(results.size());
        for (com.marketplace.identity.spi.BulkRowResult result : results) {
            wireResults.add(com.marketplace.app.graphql.generated.types.CustomerBulkResult
                    .newBuilder()
                    .customer(result.success() ? userById(result.userId()) : null)
                    .errors(result.success() ? List.of() : List.of(bulkError(result)))
                    .build());
        }
        return CustomerBulkUpdate.newBuilder()
                .count((int) results.stream().filter(com.marketplace.identity.spi.BulkRowResult::success).count())
                .results(wireResults)
                .errors(List.of())
                .build();
    }

    // ---------------------------------------------------------------------
    // Staff
    // ---------------------------------------------------------------------

    @DgsMutation(field = DgsConstants.MUTATION.StaffCreate)
    @RateLimiter(name = "graphqlAccountMutation")
    public StaffCreate staffCreate(@InputArgument StaffCreateInput input) {
        if (input == null || input.getEmail() == null || input.getEmail().isBlank()) {
            throw new IllegalArgumentException("A staff email is required");
        }
        UUID userId = userManagementSpi.createStaff(currentAuthentication(),
                toFields(input), input.getRedirectUrl() != null);
        return StaffCreate.newBuilder()
                .user(userById(userId))
                .errors(mappers.emptyStaffErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.StaffUpdate)
    @RateLimiter(name = "graphqlAccountMutation")
    public StaffUpdate staffUpdate(@InputArgument String id,
                                   @InputArgument StaffUpdateInput input) {
        UUID target = decodeUserId(id, "A staff user ID is required");
        userManagementSpi.updateStaff(currentAuthentication(), target, toFields(input));
        return StaffUpdate.newBuilder()
                .user(userById(target))
                .errors(mappers.emptyStaffErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.StaffDelete)
    @RateLimiter(name = "graphqlAccountMutation")
    public StaffDelete staffDelete(@InputArgument String id) {
        UUID target = decodeUserId(id, "A staff user ID is required");
        userManagementSpi.deleteStaff(currentAuthentication(), target);
        return StaffDelete.newBuilder()
                .user(null)
                .errors(mappers.emptyStaffErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.StaffBulkDelete)
    @RateLimiter(name = "graphqlAccountMutation")
    public StaffBulkDelete staffBulkDelete(@InputArgument List<String> ids) {
        List<UUID> targets = decodeIds(ids, "A list of staff user IDs is required");
        int count = userManagementSpi.bulkDeleteStaff(currentAuthentication(), targets);
        return StaffBulkDelete.newBuilder()
                .count(count)
                .errors(mappers.emptyStaffErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.UserBulkSetActive)
    @RateLimiter(name = "graphqlAccountMutation")
    public UserBulkSetActive userBulkSetActive(@InputArgument List<String> ids,
                                              @InputArgument Boolean isActive) {
        List<UUID> targets = decodeIds(ids, "A list of user IDs is required");
        if (isActive == null) {
            throw new IllegalArgumentException("The isActive flag is required");
        }
        int count = userManagementSpi.bulkSetActive(currentAuthentication(), targets, isActive);
        return UserBulkSetActive.newBuilder()
                .count(count)
                .errors(mappers.emptyAccountErrors())
                .build();
    }

    // ---------------------------------------------------------------------
    // Staff notification recipients
    // ---------------------------------------------------------------------

    @DgsMutation(field = DgsConstants.MUTATION.StaffNotificationRecipientCreate)
    @RateLimiter(name = "graphqlAccountMutation")
    public StaffNotificationRecipientCreate staffNotificationRecipientCreate(
            @InputArgument StaffNotificationRecipientInput input) {
        return StaffNotificationRecipientCreate.newBuilder()
                .staffNotificationRecipient(mappers.toGraphQlRecipient(
                        userManagementSpi.createRecipient(currentAuthentication(),
                                decodeNullableUserId(input == null ? null : input.getUser()),
                                input == null ? null : input.getEmail(),
                                input == null ? null : input.getActive())))
                .errors(mappers.emptyShopErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.StaffNotificationRecipientUpdate)
    @RateLimiter(name = "graphqlAccountMutation")
    public StaffNotificationRecipientUpdate staffNotificationRecipientUpdate(
            @InputArgument String id,
            @InputArgument StaffNotificationRecipientInput input) {
        return StaffNotificationRecipientUpdate.newBuilder()
                .staffNotificationRecipient(mappers.toGraphQlRecipient(
                        userManagementSpi.updateRecipient(currentAuthentication(),
                                decodeRecipientId(id),
                                decodeNullableUserId(input == null ? null : input.getUser()),
                                input == null ? null : input.getEmail(),
                                input == null ? null : input.getActive())))
                .errors(mappers.emptyShopErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.StaffNotificationRecipientDelete)
    @RateLimiter(name = "graphqlAccountMutation")
    public StaffNotificationRecipientDelete staffNotificationRecipientDelete(
            @InputArgument String id) {
        return StaffNotificationRecipientDelete.newBuilder()
                .staffNotificationRecipient(mappers.toGraphQlRecipient(
                        userManagementSpi.deleteRecipient(currentAuthentication(),
                                decodeRecipientId(id))))
                .errors(mappers.emptyShopErrors())
                .build();
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private com.marketplace.app.graphql.generated.types.CustomerBulkUpdateError bulkError(
            com.marketplace.identity.spi.BulkRowResult result) {
        return com.marketplace.app.graphql.generated.types.CustomerBulkUpdateError.newBuilder()
                .field(null)
                .message(result.errorMessage())
                .code(com.marketplace.app.graphql.generated.types.CustomerBulkUpdateErrorCode
                        .valueOf(result.errorCode()))
                .build();
    }

    private UserWriteFields toFields(UserCreateInput input) {
        return new UserWriteFields(input.getEmail(),
                input.getFirstName(), input.getLastName(), input.getIsActive());
    }

    private UserWriteFields toFields(CustomerInput input) {
        return new UserWriteFields(input == null ? null : input.getEmail(),
                input == null ? null : input.getFirstName(),
                input == null ? null : input.getLastName(),
                input == null ? null : input.getIsActive());
    }

    private UserWriteFields toFields(StaffCreateInput input) {
        return new UserWriteFields(input.getEmail(), input.getFirstName(), input.getLastName(),
                input.getIsActive());
    }

    private UserWriteFields toFields(StaffUpdateInput input) {
        return new UserWriteFields(input.getEmail(), input.getFirstName(), input.getLastName(),
                input.getIsActive());
    }

    private com.marketplace.app.graphql.generated.types.User userById(UUID userId) {
        return identitySpi.findUserAccountById(userId, currentAuthentication())
                .map(userMapper::toGraphQlUser)
                .orElse(null);
    }

    private UUID resolveTarget(String externalReference, String id) {
        if (externalReference != null && !externalReference.isBlank()) {
            return identitySpi.findUserAccountBySubject(externalReference, currentAuthentication())
                    .map(com.marketplace.identity.spi.UserAccountView::id)
                    .orElseThrow(() -> new com.marketplace.shared.api.ResourceNotFoundException(
                            "No user for the external reference: " + externalReference));
        }
        return decodeUserId(id, "A user ID or an external reference is required");
    }

    private List<UUID> decodeIds(List<String> ids, String message) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return ids.stream().map(id -> decodeUserId(id, message)).toList();
    }

    private UUID decodeUserId(String id, String message) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return globalIdCodec.decode(id, "User");
    }

    private UUID decodeNullableUserId(String id) {
        return id == null || id.isBlank() ? null : globalIdCodec.decode(id, "User");
    }

    private UUID decodeRecipientId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A staff notification recipient ID is required");
        }
        return globalIdCodec.decode(id, "StaffNotificationRecipient");
    }

    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.authentication
                    .AuthenticationCredentialsNotFoundException(
                    "Authentication is required for user administration");
        }
        return authentication;
    }
}
