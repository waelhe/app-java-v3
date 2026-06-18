package com.marketplace.identity;

import com.marketplace.identity.spi.IdentityAdminSpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for admin user management.
 * <p>Provides endpoints for:
 * <ul>
 *   <li>List all users (paginated)</li>
 *   <li>Suspend / reactivate user accounts</li>
 *   <li>Update user roles</li>
 *   <li>View audit logs (by user, by event type, or all)</li>
 * </ul>
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html">Spring Security Method Security</a>
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1 + "/admin/identity", version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class IdentityAdminController {

    private final IdentitySpi identitySpi;
    private final IdentityAdminSpi adminSpi;
    private final UserService userService;
    private final BruteForceProtectionService bruteForceService;

    public IdentityAdminController(IdentitySpi identitySpi,
                                     IdentityAdminSpi adminSpi,
                                     UserService userService,
                                     BruteForceProtectionService bruteForceService) {
        this.identitySpi = identitySpi;
        this.adminSpi = adminSpi;
        this.userService = userService;
        this.bruteForceService = bruteForceService;
    }

    /**
     * Lists all users (paginated).
     */
    @GetMapping
    public ResponseEntity<Page<UserSummary>> listUsers(Pageable pageable) {
        return ResponseEntity.ok(identitySpi.findAllSummaries(pageable));
    }

    /**
     * Suspends a user account.
     */
    @PostMapping("/{id}/suspend")
    public ResponseEntity<Void> suspendUser(@PathVariable UUID id) {
        identitySpi.suspendUser(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reactivates a suspended user account.
     */
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivateUser(@PathVariable UUID id) {
        identitySpi.reactivateUser(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Unlocks a locked account (resets brute force counter).
     */
    @PostMapping("/{id}/unlock")
    public ResponseEntity<Void> unlockUser(@PathVariable UUID id) {
        User user = userService.getById(id);
        bruteForceService.unlockAccount(user.getEmail());
        return ResponseEntity.noContent().build();
    }

    /**
     * Updates a user's role.
     */
    @PostMapping("/{id}/role")
    public ResponseEntity<Void> updateRole(@PathVariable UUID id, @RequestParam String role) {
        identitySpi.updateUserRole(id, role);
        return ResponseEntity.noContent().build();
    }

    /**
     * Views audit logs for a specific user.
     */
    @GetMapping("/{id}/audit-logs")
    public ResponseEntity<Page<IdentityAdminSpi.AuditLogEntry>> userAuditLogs(
            @PathVariable UUID id, Pageable pageable) {
        User user = userService.getById(id);
        return ResponseEntity.ok(adminSpi.findAuditLogsByUsername(user.getEmail(), pageable));
    }

    /**
     * Views all audit logs (paginated).
     */
    @GetMapping("/audit-logs")
    public ResponseEntity<Page<IdentityAdminSpi.AuditLogEntry>> allAuditLogs(Pageable pageable) {
        return ResponseEntity.ok(adminSpi.findAllAuditLogs(pageable));
    }

    /**
     * Views audit logs by event type.
     */
    @GetMapping("/audit-logs/by-type")
    public ResponseEntity<Page<IdentityAdminSpi.AuditLogEntry>> auditLogsByType(
            @RequestParam AuthEventType type, Pageable pageable) {
        return ResponseEntity.ok(adminSpi.findAuditLogsByEventType(type, pageable));
    }
}
