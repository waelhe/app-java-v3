package com.marketplace.identity;

import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for session management.
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Checking active sessions count</li>
 *   <li>Revoking all sessions for a user</li>
 * </ul>
 *
 * @see <a href="https://docs.spring.io/spring-authorization-server/reference/core-model.html">Spring Authorization Server Core Model</a>
 */
@RestController
@RequestMapping("/api/v1/users/me/sessions")
@PreAuthorize("isAuthenticated()")
public class SessionController {

    private final AuthAuditService auditService;
    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;
    private final JdbcTemplate jdbcTemplate;

    public SessionController(AuthAuditService auditService,
                              UserService userService,
                              CurrentUserProvider currentUserProvider,
                              JdbcTemplate jdbcTemplate) {
        this.auditService = auditService;
        this.userService = userService;
        this.currentUserProvider = currentUserProvider;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Revokes all active sessions/tokens for the current user.
     * <p>Deletes all OAuth2 authorizations for this user from the database.
     */
    @DeleteMapping
    public ResponseEntity<Void> revokeAllSessions(Authentication auth) {
        UUID userId = currentUserProvider.getCurrentUserId(auth);
        User user = userService.getById(userId);

        // Delete all OAuth2 authorizations for this principal
        int deleted = jdbcTemplate.update(
                "DELETE FROM oauth2_authorization WHERE principal_name = ?",
                user.getEmail()
        );

        auditService.log(user.getEmail(), AuthEventType.PASSWORD_CHANGED,
                "All sessions revoked (" + deleted + " sessions removed)");

        return ResponseEntity.noContent().build();
    }

    /**
     * Checks if the current user has any active sessions.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> sessionStatus(Authentication auth) {
        UUID userId = currentUserProvider.getCurrentUserId(auth);
        User user = userService.getById(userId);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_authorization WHERE principal_name = ?",
                Integer.class, user.getEmail()
        );

        long activeSessions = count != null ? count : 0;

        return ResponseEntity.ok(Map.of(
                "activeSessions", activeSessions,
                "message", activeSessions > 0 ? "Active sessions found" : "No active sessions"
        ));
    }
}
