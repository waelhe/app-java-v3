package com.marketplace.identity.spi;

import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * additive user-administration seam of the {@code identity-spi} named
 * interface — the staff-managed customer/staff lifecycle
 * ({@code customer*}/{@code staff*}/{@code userBulkSetActive}) and the
 * staff notification recipient registry. ADMIN-enforced at the owning use
 * cases (the RBAC mapping row).
 */
@NamedInterface("identity-spi")
public interface UserManagementSpi {

    UUID createCustomer(Authentication caller, UserWriteFields fields, boolean sendSetPassword);

    UUID updateCustomer(Authentication caller, UUID userId, UserWriteFields fields);

    UUID deleteCustomer(Authentication caller, UUID userId);

    int bulkDeleteCustomers(Authentication caller, List<UUID> userIds);

    List<BulkRowResult> bulkUpdateCustomers(Authentication caller, List<UUID> userIds,
                                            List<UserWriteFields> rows, boolean ignoreFailed);

    UUID createStaff(Authentication caller, UserWriteFields fields, boolean sendSetPassword);

    UUID updateStaff(Authentication caller, UUID userId, UserWriteFields fields);

    UUID deleteStaff(Authentication caller, UUID userId);

    int bulkDeleteStaff(Authentication caller, List<UUID> userIds);

    int bulkSetActive(Authentication caller, List<UUID> userIds, boolean active);

    RecipientView createRecipient(Authentication caller, UUID userId, String email, Boolean active);

    RecipientView updateRecipient(Authentication caller, UUID id, UUID userId, String email,
                                  Boolean active);

    RecipientView deleteRecipient(Authentication caller, UUID id);

    RecipientView findRecipient(Authentication caller, UUID id);

    List<RecipientView> listRecipients(Authentication caller);
}
