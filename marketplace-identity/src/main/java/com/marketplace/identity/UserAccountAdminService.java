package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import com.marketplace.identity.spi.BulkRowResult;
import com.marketplace.identity.spi.UserWriteFields;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * staff-managed user lifecycle behind the Saleor Users-domain mutations —
 * {@code customerCreate/Update/Delete/BulkDelete/BulkUpdate},
 * {@code staffCreate/Update/Delete/BulkDelete} and
 * {@code userBulkSetActive}. ADMIN-enforced at this owning use-case
 * boundary (Saleor requires MANAGE_USERS/MANAGE_STAFF — the platform's
 * mapped ADMIN capability, the RBAC mapping divergence row).
 *
 * <p><b>The platform's own semantics, reused:</b> creation rides the
 * standing {@code UserService#register} flow with a generated unknowable
 * secret (the customer's set-password journey is the emailed reset right
 * — Saleor's ACCOUNT_SET_PASSWORD_REQUESTED shape); deletion is the
 * standing I7 erasure ({@code pseudonymizeAccount}); activation flips the
 * login row through {@code UserService#updateUserStatus} with its
 * last-active-ADMIN guard. Staff accounts are the platform's backoffice
 * population (the ADMIN role — the merged {@code UserAccountView.staff}
 * truth); sellers (PROVIDER) are NOT staff on this platform.</p>
 *
 * <p><b>The bulk legs</b> honor Saleor's error policy exactly:
 * REJECT_EVERYTHING (the default) is all-or-nothing in one transaction;
 * IGNORE_FAILED applies row-by-row and answers per-row results.</p>
 */
@Service
@Transactional
public class UserAccountAdminService implements com.marketplace.identity.spi.UserManagementSpi {

    private static final Logger log = LoggerFactory.getLogger(UserAccountAdminService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final UserDetailsManager userDetailsManager;
    private final UserService userService;
    private final PasswordResetService passwordResetService;
    private final StaffNotificationRecipientService recipientService;

    public UserAccountAdminService(UserRepository userRepository,
                                    UserDetailsManager userDetailsManager,
                                    UserService userService,
                                    PasswordResetService passwordResetService,
                                    StaffNotificationRecipientService recipientService) {
        this.userRepository = userRepository;
        this.userDetailsManager = userDetailsManager;
        this.userService = userService;
        this.passwordResetService = passwordResetService;
        this.recipientService = recipientService;
    }


    // ---------------------------------------------------------------------
    // Customers
    // ---------------------------------------------------------------------

    /**
     * {@code customerCreate}: the CONSUMER account — both stores via the
     * standing register flow, the generated secret unknowable, the
     * set-password right emailed (Saleor's invite semantics).
     */
    @Observed(name = "customer.admin.create")
    public java.util.UUID createCustomer(Authentication caller, UserWriteFields fields, boolean sendSetPassword) {
        requireAdmin(caller);
        String email = requireEmail(fields.email());
        String displayName = fields.displayName();
        if (displayName != null && displayName.length() > 200) {
            throw new IllegalArgumentException("The display name exceeds 200 characters");
        }
        byte[] secret = new byte[32];
        SECURE_RANDOM.nextBytes(secret);
        String generated = Base64.getEncoder().encodeToString(secret);
        User created = userService.register(email, generated, displayName);
        if (sendSetPassword) {
            passwordResetService.requestReset(email);
        }
        log.info("Customer created: userId={}, email={}, actor={}",
                created.getId(), email, caller.getName());
        return created.getId();
    }

    /** {@code customerUpdate}: profile + email + activation on a customer account. */
    @Observed(name = "customer.admin.update")
    public java.util.UUID updateCustomer(Authentication caller, UUID userId, UserWriteFields fields) {
        requireAdmin(caller);
        User user = requireUser(userId);
        requireRole(user, UserRole.CONSUMER, "customer");
        if (fields.email() != null && !fields.email().isBlank()
                && !fields.email().trim().equalsIgnoreCase(nullSafe(user.getEmail()))) {
            moveEmail(user, fields.email().trim());
        }
        String displayName = fields.displayName();
        if (displayName != null) {
            user.updateProfile(null, displayName);
        }
        if (fields.isActive() != null) {
            applyActivation(user, fields.isActive());
        }
        log.info("Customer updated: userId={}, actor={}", userId, caller.getName());
        return user.getId();
    }

    /** {@code customerDelete}: the standing I7 erasure. */
    @Observed(name = "customer.admin.delete")
    public java.util.UUID deleteCustomer(Authentication caller, UUID userId) {
        requireAdmin(caller);
        User user = requireUser(userId);
        requireRole(user, UserRole.CONSUMER, "customer");
        userService.pseudonymizeAccount(userId, "customer deletion (dashboard)", caller.getName());
        return user.getId();
    }

    /** {@code customerBulkDelete}: row-by-row erasure, honest count. */
    @Observed(name = "customer.admin.bulk_delete")
    public int bulkDeleteCustomers(Authentication caller, List<UUID> userIds) {
        requireAdmin(caller);
        int count = 0;
        for (UUID id : userIds) {
            Optional<User> user = userRepository.findById(id)
                    .filter(row -> row.getRole() == UserRole.CONSUMER);
            if (user.isPresent()) {
                userService.pseudonymizeAccount(id, "customer bulk deletion (dashboard)",
                        caller.getName());
                count++;
            }
        }
        log.info("Customers bulk-deleted: count={}, actor={}", count, caller.getName());
        return count;
    }

    /** {@code customerBulkUpdate}: the error-policy semantics exactly. */
    @Observed(name = "customer.admin.bulk_update")
    public List<BulkRowResult> bulkUpdateCustomers(Authentication caller,
                                                   List<UUID> userIds,
                                                   List<UserWriteFields> rows,
                                                   boolean ignoreFailed) {
        requireAdmin(caller);
        List<BulkRowResult> results = new ArrayList<>(userIds.size());
        for (int i = 0; i < userIds.size(); i++) {
            UUID id = userIds.get(i);
            UserWriteFields fields = rows.get(i);
            try {
                updateCustomer(caller, id, fields);
                results.add(new BulkRowResult(id, null, null));
            } catch (com.marketplace.shared.api.ResourceNotFoundException failure) {
                if (!ignoreFailed) {
                    throw failure;
                }
                results.add(new BulkRowResult(id, "NOT_FOUND", failure.getMessage()));
            } catch (com.marketplace.shared.api.ConflictException failure) {
                if (!ignoreFailed) {
                    throw failure;
                }
                results.add(new BulkRowResult(id, "ALREADY_EXISTS", failure.getMessage()));
            } catch (IllegalArgumentException failure) {
                if (!ignoreFailed) {
                    throw failure;
                }
                results.add(new BulkRowResult(id, "INVALID", failure.getMessage()));
            }
        }
        return results;
    }

    // ---------------------------------------------------------------------
    // Staff
    // ---------------------------------------------------------------------

    /** {@code staffCreate}: a backoffice (ADMIN-role) account with the set-password email. */
    @Observed(name = "staff.admin.create")
    public java.util.UUID createStaff(Authentication caller, UserWriteFields fields, boolean sendSetPassword) {
        requireAdmin(caller);
        String email = requireEmail(fields.email());
        String displayName = fields.displayName();
        byte[] secret = new byte[32];
        SECURE_RANDOM.nextBytes(secret);
        User created = userService.registerStaff(email,
                Base64.getEncoder().encodeToString(secret), displayName,
                fields.isActive() == null || fields.isActive());
        if (sendSetPassword) {
            passwordResetService.requestReset(email);
        }
        log.info("Staff created: userId={}, email={}, actor={}",
                created.getId(), email, caller.getName());
        return created.getId();
    }

    /** {@code staffUpdate}: profile + email + activation on a staff account. */
    @Observed(name = "staff.admin.update")
    public java.util.UUID updateStaff(Authentication caller, UUID userId, UserWriteFields fields) {
        requireAdmin(caller);
        User user = requireUser(userId);
        requireRole(user, UserRole.ADMIN, "staff");
        if (fields.email() != null && !fields.email().isBlank()
                && !fields.email().trim().equalsIgnoreCase(nullSafe(user.getEmail()))) {
            moveEmail(user, fields.email().trim());
        }
        String displayName = fields.displayName();
        if (displayName != null) {
            user.updateProfile(null, displayName);
        }
        if (fields.isActive() != null) {
            applyActivation(user, fields.isActive());
        }
        log.info("Staff updated: userId={}, actor={}", userId, caller.getName());
        return user.getId();
    }

    /** {@code staffDelete}: the standing erasure (the last-ADMIN guard protects). */
    @Observed(name = "staff.admin.delete")
    public java.util.UUID deleteStaff(Authentication caller, UUID userId) {
        requireAdmin(caller);
        User user = requireUser(userId);
        requireRole(user, UserRole.ADMIN, "staff");
        userService.pseudonymizeAccount(userId, "staff deletion (dashboard)", caller.getName());
        return user.getId();
    }

    /** {@code staffBulkDelete}: row-by-row erasure, honest count. */
    @Observed(name = "staff.admin.bulk_delete")
    public int bulkDeleteStaff(Authentication caller, List<UUID> userIds) {
        requireAdmin(caller);
        int count = 0;
        for (UUID id : userIds) {
            Optional<User> user = userRepository.findById(id)
                    .filter(row -> row.getRole() == UserRole.ADMIN);
            if (user.isPresent()) {
                userService.pseudonymizeAccount(id, "staff bulk deletion (dashboard)",
                        caller.getName());
                count++;
            }
        }
        log.info("Staff bulk-deleted: count={}, actor={}", count, caller.getName());
        return count;
    }

    // ---------------------------------------------------------------------
    // Activation
    // ---------------------------------------------------------------------

    /** {@code userBulkSetActive}: the standing status flip, bulk, honest count. */
    @Observed(name = "user.admin.bulk_set_active")
    public int bulkSetActive(Authentication caller, List<UUID> userIds, boolean active) {
        requireAdmin(caller);
        int count = 0;
        for (UUID id : userIds) {
            if (userRepository.existsById(id)) {
                userService.updateUserStatus(id, active ? "ENABLED" : "DISABLED",
                        "dashboard bulk set-active", caller.getName());
                count++;
            }
        }
        log.info("Users bulk set-active: count={}, active={}, actor={}",
                count, active, caller.getName());
        return count;
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /** The login-side email move (createUser at the new name + old row death). */
    private void moveEmail(User user, String newEmail) {
        if (userRepository.existsBySubject(newEmail) || userDetailsManager.userExists(newEmail)) {
            throw new ConflictException("The email address already owns an account: " + newEmail);
        }
        UserDetails stored;
        try {
            stored = userDetailsManager.loadUserByUsername(user.getSubject());
        } catch (UsernameNotFoundException ex) {
            throw new ResourceNotFoundException(
                    "No authentication account for user: " + user.getId());
        }
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(newEmail)
                .password(stored.getPassword())
                .authorities(stored.getAuthorities())
                .accountExpired(!stored.isAccountNonExpired())
                .accountLocked(!stored.isAccountNonLocked())
                .credentialsExpired(!stored.isCredentialsNonExpired())
                .disabled(!stored.isEnabled())
                .build());
        userDetailsManager.deleteUser(user.getSubject());
        user.updateProfile(newEmail, null);
    }

    private void applyActivation(User user, boolean active) {
        userService.updateUserStatus(user.getId(), active ? "ENABLED" : "DISABLED",
                "dashboard account update", "dashboard");
    }

    private static String requireEmail(String email) {
        if (email == null || email.isBlank() || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("A valid email address is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No user " + userId));
    }

    private static void requireRole(User user, UserRole expected, String surface) {
        if (user.getRole() != expected) {
            throw new ConflictException(
                    "The account is not a " + surface + " (role: " + user.getRole() + ")");
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static void requireAdmin(Authentication caller) {
        boolean admin = caller.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!admin) {
            throw new AccessDeniedException(
                    "User administration requires the ADMIN capability");
        }
    }


    // ---------------------------------------------------------------------
    // Staff notification recipients (UserManagementSpi delegation)
    // ---------------------------------------------------------------------

    @Override
    public com.marketplace.identity.spi.RecipientView createRecipient(Authentication caller,
            UUID userId, String email, Boolean active) {
        return recipientService.create(caller, userId, email, active);
    }

    @Override
    public com.marketplace.identity.spi.RecipientView updateRecipient(Authentication caller,
            UUID id, UUID userId, String email, Boolean active) {
        return recipientService.update(caller, id, userId, email, active);
    }

    @Override
    public com.marketplace.identity.spi.RecipientView deleteRecipient(Authentication caller, UUID id) {
        return recipientService.delete(caller, id);
    }

    @Override
    public com.marketplace.identity.spi.RecipientView findRecipient(Authentication caller, UUID id) {
        return recipientService.find(caller, id).orElse(null);
    }

    @Override
    public List<com.marketplace.identity.spi.RecipientView> listRecipients(Authentication caller) {
        return recipientService.list(caller);
    }
}
