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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final AuthActionTokenService authActionTokenService;
    /**
     * Agent 1 (the CodeRabbit integrity round — {@code customerBulkUpdate}'s
     * IGNORE_FAILED policy): the per-row REQUIRES_NEW template, built per
     * the official programmatic-transaction pattern (TransactionTemplate
     * over the platform's single {@link PlatformTransactionManager}). Each
     * row commits or rolls back on its own, so a failing row neither marks
     * the shared transaction rollback-only (the local catch used to record
     * a per-row error and then die on {@code UnexpectedRollbackException})
     * nor commits its own half-applied writes (the email move's new login
     * row used to survive a later failure in the same row).
     */
    private final TransactionTemplate perRowTransaction;

    public UserAccountAdminService(UserRepository userRepository,
                                    UserDetailsManager userDetailsManager,
                                    UserService userService,
                                    PasswordResetService passwordResetService,
                                    StaffNotificationRecipientService recipientService,
                                    AuthActionTokenService authActionTokenService,
                                    PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.userDetailsManager = userDetailsManager;
        this.userService = userService;
        this.passwordResetService = passwordResetService;
        this.recipientService = recipientService;
        this.authActionTokenService = authActionTokenService;
        this.perRowTransaction = new TransactionTemplate(transactionManager);
        this.perRowTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

    /**
     * {@code customerBulkUpdate}: the error-policy semantics exactly.
     * REJECT_EVERYTHING (the default) stays all-or-nothing in the ONE
     * class-level transaction; IGNORE_FAILED runs each row in its own
     * REQUIRES_NEW transaction (the CodeRabbit integrity round) — a
     * failing row rolls back its own partial writes, records its per-row
     * result, and NEVER marks the caller's transaction rollback-only.
     */
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
            if (!ignoreFailed) {
                updateCustomer(caller, id, fields);
                results.add(new BulkRowResult(id, null, null));
                continue;
            }
            try {
                perRowTransaction.executeWithoutResult(
                        status -> updateCustomer(caller, id, fields));
                results.add(new BulkRowResult(id, null, null));
            } catch (com.marketplace.shared.api.ResourceNotFoundException failure) {
                results.add(new BulkRowResult(id, "NOT_FOUND", failure.getMessage()));
            } catch (com.marketplace.shared.api.ConflictException failure) {
                results.add(new BulkRowResult(id, "ALREADY_EXISTS", failure.getMessage()));
            } catch (IllegalArgumentException failure) {
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

    /**
     * The login-side email move (createUser at the new name + old row
     * death + the projection row's identity move). The CodeRabbit
     * integrity round closed the three missing legs: the subject moves
     * TOGETHER with the email (the platform's subject==username
     * invariant — moving only the email broke every findBySubject
     * lookup), the V112 token history purges BEFORE the old login row
     * dies (its FK would reject the delete otherwise), and the old
     * principal's authorizations + the user caches follow through the
     * shared {@link UserService#loginIdentityMoved} aftermath.
     */
    private void moveEmail(User user, String newEmail) {
        // The round-2 CodeRabbit finding: the move path rides the SAME
        // requireEmail contract as the create paths — the address shape AND
        // the 50-character login-store domain are validated before any
        // write (an arbitrary string or an over-long address would otherwise
        // become the login username and users.subject, and fail at
        // createUser with a storage error).
        String normalized = requireEmail(newEmail);
        if (userRepository.existsBySubject(normalized) || userDetailsManager.userExists(normalized)) {
            throw new ConflictException("The email address already owns an account: " + normalized);
        }
        String oldSubject = user.getSubject();
        UserDetails stored;
        try {
            stored = userDetailsManager.loadUserByUsername(oldSubject);
        } catch (UsernameNotFoundException ex) {
            throw new ResourceNotFoundException(
                    "No authentication account for user: " + user.getId());
        }
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(normalized)
                .password(stored.getPassword())
                .authorities(stored.getAuthorities())
                .accountExpired(!stored.isAccountNonExpired())
                .accountLocked(!stored.isAccountNonLocked())
                .credentialsExpired(!stored.isCredentialsNonExpired())
                .disabled(!stored.isEnabled())
                .build());
        // V112's FK leg: every token row for the old subject still
        // references the login row — the history purges BEFORE the row
        // dies (the Envers DEL revisions keep the redemption audit
        // trail).
        authActionTokenService.purgeHistory(oldSubject);
        userDetailsManager.deleteUser(oldSubject);
        user.changeLoginIdentity(normalized);
        // The shared aftermath: the old principal's authorizations die
        // with their refresh grants, and the two user caches drop their
        // stale subject-keyed copies.
        userService.loginIdentityMoved(oldSubject);
    }

    private void applyActivation(User user, boolean active) {
        userService.updateUserStatus(user.getId(), active ? "ENABLED" : "DISABLED",
                "dashboard account update", "dashboard");
    }

    private static String requireEmail(String email) {
        // The round-2 CodeRabbit finding: the length cap lives HERE so the
        // create paths and the move path share it — auth_users.username is
        // VARCHAR(50) (V13), the login store's own domain.
        if (email == null || email.isBlank()
                || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
                || email.trim().length() > 50) {
            throw new IllegalArgumentException(
                    "A valid email address of at most 50 characters is required");
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
