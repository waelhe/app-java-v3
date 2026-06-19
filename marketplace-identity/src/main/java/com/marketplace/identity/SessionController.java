package com.marketplace.identity;

import com.marketplace.shared.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for session management.
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Checking active sessions count</li>
 *   <li>Revoking all sessions for a user (via {@link OAuth2AuthorizationService#remove})</li>
 * </ul>
 *
 * <p><b>Token revocation</b>: uses {@link OAuth2AuthorizationService#remove(OAuth2Authorization)}
 * instead of raw JDBC DELETE. This ensures the authorization is properly removed from both
 * the JDBC store and any in-memory caches, and that introspection (RFC 7662) and revocation
 * (RFC 7009) endpoints reflect the change immediately.
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/server-authorization/core-model.html">Spring Authorization Server Core Model</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7009">RFC 7009 — Token Revocation</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7662">RFC 7662 — Token Introspection</a>
 */
@RestController
@RequestMapping("/api/v1/users/me/sessions")
@PreAuthorize("isAuthenticated()")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final AuthAuditService auditService;
    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;
    private final JdbcTemplate jdbcTemplate;
    private final OAuth2AuthorizationService authorizationService;

    public SessionController(AuthAuditService auditService,
                              UserService userService,
                              CurrentUserProvider currentUserProvider,
                              JdbcTemplate jdbcTemplate,
                              OAuth2AuthorizationService authorizationService) {
        this.auditService = auditService;
        this.userService = userService;
        this.currentUserProvider = currentUserProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
    }

    /**
     * Revokes all active sessions/tokens for the current user.
     *
     * <p>Loads all OAuth2 authorization IDs for this principal from the JDBC store,
     * then removes each one via {@link OAuth2AuthorizationService#remove}. This is
     * the documented way to revoke tokens in Spring Authorization Server — raw
     * JDBC DELETE would bypass the service's internal bookkeeping and the
     * introspection (RFC 7662) / revocation (RFC 7009) endpoint logic.
     */
    @DeleteMapping
    public ResponseEntity<Void> revokeAllSessions(Authentication auth) {
        UUID userId = currentUserProvider.getCurrentUserId(auth);
        User user = userService.getById(userId);
        String principalName = user.getEmail();

        // Query all authorization IDs for this principal.
        List<String> authorizationIds = jdbcTemplate.queryForList(
                "SELECT id FROM oauth2_authorization WHERE principal_name = ?",
                String.class, principalName);

        int revoked = 0;
        for (String authId : authorizationIds) {
            try {
                // findById loads the full authorization, then remove() deletes it
                // through the service — the documented revocation path.
                OAuth2Authorization authorization = authorizationService.findById(authId);
                if (authorization != null) {
                    authorizationService.remove(authorization);
                    revoked++;
                }
            } catch (Exception e) {
                // Pass 'e' as last arg so SLF4J prints the full stack trace for diagnostics.
                log.warn("Failed to revoke authorization id={} for principal={}",
                        authId, principalName, e);
            }
        }

        auditService.log(principalName, AuthEventType.SESSION_REVOKED,
                "All sessions revoked (" + revoked + " sessions removed)");

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
