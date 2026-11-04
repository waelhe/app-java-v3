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
import com.marketplace.shared.api.ResourceNotFoundException;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final GraphQlProperties properties;

    public UserManagementDataFetcher(UserManagementSpi userManagementSpi,
                                     IdentitySpi identitySpi,
                                     AgentOneGraphQlMappers mappers,
                                     UserGraphQlMapper userMapper,
                                     SaleorGlobalIdCodec globalIdCodec,
                                     GraphQlProperties properties) {
        this.userManagementSpi = userManagementSpi;
        this.identitySpi = identitySpi;
        this.mappers = mappers;
        this.userMapper = userMapper;
        this.globalIdCodec = globalIdCodec;
        this.properties = properties;
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
        properties.requireBoundedList(ids, "customerBulkDelete");
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
        properties.requireBoundedList(customers, "customerBulkUpdate");
        if (customers == null || customers.isEmpty()) {
            throw new IllegalArgumentException("A list of customer updates is required");
        }
        // The 2026-11-02 #806 review (Minor · Functional Correctness):
        // the row-level policies are BOTH of Saleor's contract members
        // that keep the valid rows — IGNORE_FAILED and
        // REJECT_FAILED_ROWS ride the SAME per-row mode at the identity
        // SPI (its boolean answers row-level vs all-or-nothing), while
        // REJECT_EVERYTHING (the null default) alone stays
        // all-or-nothing. Silently converting REJECT_FAILED_ROWS to the
        // all-or-nothing mode broke the pinned contract: one
        // unresolvable row threw and failed the whole batch instead of
        // failing ITSELF.
        boolean rowLevel = errorPolicy == ErrorPolicyEnum.IGNORE_FAILED
                || errorPolicy == ErrorPolicyEnum.REJECT_FAILED_ROWS;
        List<UUID> ids = new ArrayList<>(customers.size());
        List<UserWriteFields> rows = new ArrayList<>(customers.size());
        // The 2026-11-03 #806 review (Minor · Functional Correctness): the
        // row-level catch used to discard the row failure's class and
        // message (a null-pairing handed the owner), so the client saw a
        // generic INVALID for every failed row — a NOT_FOUND for an
        // unknown externalReference answered as an INVALID. The failure
        // is preserved by its row index here and answers ITS OWN
        // classified code and authored message in results[i].errors.
        Map<Integer, RuntimeException> transportFailures = new HashMap<>();
        for (CustomerBulkUpdateInput row : customers) {
            // The shard review (Minor · Functional Correctness, 2026-11-01):
            // with a row-level policy, one unresolvable row must fail
            // ITSELF — never the whole batch. Saleor's errorPolicy
            // contract keeps the valid rows and reports the failure
            // per-row in results[i].errors; a null id answers the
            // per-row failure. The wave-3 review moved toFields INSIDE
            // the same guard: its classified not-implemented rejections
            // (the wave-2 honesty fix) are row-level validation
            // failures — one bad row must fail itself, not the batch
            // (the identity owner's bulkUpdateCustomers answers the
            // per-row INVALID for a null id — the null-pairing path the
            // catch ladder already uses).
            try {
                // IDP-02 (the 2026-11-03 adversarial scan, P2): resolve
                // BOTH row halves into locals BEFORE any list add. The
                // previous shape added the resolved id to `ids` first and
                // converted the fields second — a row whose id resolved
                // but whose toFields conversion failed (the classified
                // not-implemented rejections, e.g. a supplied
                // customerType) leaked its id into `ids` unpaired: the
                // catch's null-pairing then appended id=null/row=null,
                // leaving ids.size() = rows.size() + 1 forever after —
                // every later row shifted its pairing and
                // transportFailures' index recorded the wrong slot. The
                // local-first shape keeps the two lists paired in every
                // outcome of the row.
                java.util.UUID resolvedId = resolveTarget(row.getExternalReference(), row.getId());
                UserWriteFields fields = toFields(row.getInput());
                ids.add(resolvedId);
                rows.add(fields);
            } catch (IllegalArgumentException
                    | ResourceNotFoundException rowFailure) {
                // The narrowed catch (the 2026-11-02 #806 review, Minor ·
                // Stability & Availability): ONLY the two semantic
                // row-failure classes the helpers above actually throw —
                // the codec's malformed-global-ID IAE, the classified
                // not-implemented rejections, and the unresolvable
                // external reference's NOT_FOUND — answer the row-level
                // null-pairing. Repository and infrastructure failures
                // (the DataAccessException family) propagate to the
                // classified GraphQL error surface in every mode instead
                // of masquerading as a failed row with paired nulls. The
                // SDL pins [CustomerBulkUpdateInput!]! — non-null
                // elements — so no null-element NPE can reach this loop.
                if (!rowLevel) {
                    throw rowFailure;
                }
                transportFailures.put(ids.size(), rowFailure);
                ids.add(null);
                rows.add(null);
            }
        }
        List<com.marketplace.identity.spi.BulkRowResult> results =
                userManagementSpi.bulkUpdateCustomers(currentAuthentication(), ids, rows,
                        rowLevel);
        List<com.marketplace.app.graphql.generated.types.CustomerBulkResult> wireResults =
                new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            com.marketplace.identity.spi.BulkRowResult result = results.get(i);
            RuntimeException transportFailure = transportFailures.get(i);
            if (transportFailure != null) {
                // The row's OWN resolution failure answers its classified
                // code and authored message (the SaleorMutationErrors
                // taxonomy every other payload error rides) — never the
                // owner's generic INVALID for a row it never saw.
                wireResults.add(com.marketplace.app.graphql.generated.types.CustomerBulkResult
                        .newBuilder()
                        .customer(null)
                        .errors(List.of(com.marketplace.app.graphql.generated.types.CustomerBulkUpdateError
                                .newBuilder()
                                .field(null)
                                .message(SaleorMutationErrors.wireMessage(transportFailure))
                                .code(SaleorMutationErrors.translate(
                                        com.marketplace.app.graphql.generated.types.CustomerBulkUpdateErrorCode.class,
                                        SaleorMutationErrors.codeOf(transportFailure)))
                                .build()))
                        .build());
                continue;
            }
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
        properties.requireBoundedList(ids, "staffBulkDelete");
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
        properties.requireBoundedList(ids, "userBulkSetActive");
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
                // The wave-7 review (Minor · Data Integrity): the payload's
                // own vocabulary is the only representable surface — a
                // native code it lacks (ALREADY_EXISTS has no
                // CustomerBulkUpdateErrorCode constant) translated through
                // the shared contract, never a raw valueOf that destroys
                // the payload build.
                .code(SaleorMutationErrors.translate(
                        com.marketplace.app.graphql.generated.types.CustomerBulkUpdateErrorCode.class,
                        result.errorCode()))
                .build();
    }

    private UserWriteFields toFields(UserCreateInput input) {
        // The shard review (Minor · Functional Correctness, 2026-11-01
        // wave 2): the SDL declares defaultBillingAddress/
        // defaultShippingAddress/customerType, but the identity owner's
        // write fields carry none of them — a supplied value was a SILENT
        // no-op (the file's own never-a-silent-no-op rule, the
        // rejectChannel precedent). The classified rejection keeps the
        // wire contract honest.
        rejectUnimplementedUserInputFields(
                input.getDefaultBillingAddress(), input.getDefaultShippingAddress(),
                input.getCustomerType(), "userCreate");
        // The 2026-11-04 #806 round (Minor · Functional Correctness): the
        // input's own channel field rode the same silent no-op — a
        // supplied channel made the create look channel-scoped while the
        // single-channel platform ignored it. The house channel message.
        if (input.getChannel() != null && !input.getChannel().isBlank()) {
            throw new IllegalArgumentException(
                    "The channel argument is not implemented on this platform yet "
                            + "(the platform is a single-channel store)");
        }
        return new UserWriteFields(input.getEmail(),
                input.getFirstName(), input.getLastName(), input.getIsActive());
    }

    private UserWriteFields toFields(CustomerInput input) {
        // The shard review (2026-11-01 wave 2): the same classified
        // rejection the UserCreateInput conversion carries — the customer
        // amendment's address/customerType fields are equally unowned.
        if (input != null) {
            rejectUnimplementedUserInputFields(
                    input.getDefaultBillingAddress(), input.getDefaultShippingAddress(),
                    input.getCustomerType(), "customerCreate/customerUpdate");
        }
        return new UserWriteFields(input == null ? null : input.getEmail(),
                input == null ? null : input.getFirstName(),
                input == null ? null : input.getLastName(),
                input == null ? null : input.getIsActive());
    }

    private UserWriteFields toFields(StaffCreateInput input) {
        // The shard review (2026-11-01 wave 2): the SDL declares
        // StaffCreateInput.addGroups — a supplied group list was silently
        // dropped (an admin granting a permission group got errors:[] and
        // no change, the exact silent no-op the file bans).
        if (input.getAddGroups() != null && !input.getAddGroups().isEmpty()) {
            throw new IllegalArgumentException(
                    "staffCreate addGroups is not implemented on this platform yet");
        }
        return new UserWriteFields(input.getEmail(), input.getFirstName(), input.getLastName(),
                input.getIsActive());
    }

    private UserWriteFields toFields(StaffUpdateInput input) {
        // The shard review (2026-11-01 wave 2): addGroups/removeGroups —
        // the same classified rejection the create path carries.
        if ((input.getAddGroups() != null && !input.getAddGroups().isEmpty())
                || (input.getRemoveGroups() != null && !input.getRemoveGroups().isEmpty())) {
            throw new IllegalArgumentException(
                    "staffUpdate addGroups/removeGroups is not implemented on this platform yet");
        }
        return new UserWriteFields(input.getEmail(), input.getFirstName(), input.getLastName(),
                input.getIsActive());
    }

    /**
     * The shared never-a-silent-no-op rejection for the user-surface's
     * unowned input fields (the shard review 2026-11-01 wave 2): a SUPPLIED
     * value answers the classified validation error, an absent one passes
     * — the file's own rejectChannel vocabulary.
     */
    private static void rejectUnimplementedUserInputFields(
            Object defaultBillingAddress, Object defaultShippingAddress,
            Object customerType, String root) {
        if (defaultBillingAddress != null) {
            throw new IllegalArgumentException(
                    root + " defaultBillingAddress is not implemented on this platform yet");
        }
        if (defaultShippingAddress != null) {
            throw new IllegalArgumentException(
                    root + " defaultShippingAddress is not implemented on this platform yet");
        }
        if (customerType != null) {
            throw new IllegalArgumentException(
                    root + " customerType is not implemented on this platform yet");
        }
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
        // B24 (IDP-06): the shared identity-family wall — an anonymous
        // token answers isAuthenticated() == true, so the TYPE check is
        // part of the refusal (the TokenDataFetcher lesson, one authority).
        return AuthenticatedCallers.require("user administration");
    }
}
