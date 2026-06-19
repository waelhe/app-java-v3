package com.marketplace.identity;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SessionController}.
 *
 * <p>Verifies that session revocation uses {@link OAuth2AuthorizationService#remove}
 * (the documented revocation path) instead of raw JDBC DELETE.
 */
@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock private AuthAuditService auditService;
    @Mock private UserService userService;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private OAuth2AuthorizationService authorizationService;
    @Mock private com.marketplace.shared.api.JwtRevocationPort jwtRevocationPort;
    @Mock private Authentication auth;

    private SessionController sessionController;

    @BeforeEach
    void setUp() {
        sessionController = new SessionController(auditService, userService,
                currentUserProvider, jdbcTemplate, authorizationService, jwtRevocationPort);
    }

    @Test
    void revokeAllSessions_removesViaAuthorizationService() {
        UUID userId = UUID.randomUUID();
        User user = User.create("sub", "user@test.com", "User", UserRole.CONSUMER);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        when(userService.getById(userId)).thenReturn(user);
        when(jdbcTemplate.queryForList(
                eq("SELECT id FROM oauth2_authorization WHERE principal_name = ?"),
                eq(String.class), eq("user@test.com")))
                .thenReturn(List.of("auth-1", "auth-2", "auth-3"));

        OAuth2Authorization mockAuthz = mock(OAuth2Authorization.class);
        when(authorizationService.findById(anyString())).thenReturn(mockAuthz);

        sessionController.revokeAllSessions(auth);

        // Verify each authorization is removed via the service (not raw JDBC DELETE).
        verify(authorizationService, times(3)).findById(anyString());
        verify(authorizationService, times(3)).remove(mockAuthz);
        // Verify raw JDBC DELETE is NOT used.
        verify(jdbcTemplate, never()).update(eq("DELETE FROM oauth2_authorization WHERE principal_name = ?"), eq("user@test.com"));
        verify(auditService).log(eq("user@test.com"), eq(AuthEventType.SESSION_REVOKED), any());
    }

    @Test
    void revokeAllSessions_skipsMissingAuthorizations() {
        UUID userId = UUID.randomUUID();
        User user = User.create("sub", "user@test.com", "User", UserRole.CONSUMER);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        when(userService.getById(userId)).thenReturn(user);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of("auth-1"));

        // findById returns null -- authorization already removed (race or re-call).
        when(authorizationService.findById("auth-1")).thenReturn(null);

        sessionController.revokeAllSessions(auth);

        verify(authorizationService).findById("auth-1");
        verify(authorizationService, never()).remove(any());
        verify(auditService).log(eq("user@test.com"), eq(AuthEventType.SESSION_REVOKED), any());
    }

    @Test
    void sessionStatus_returnsCount() {
        UUID userId = UUID.randomUUID();
        User user = User.create("sub", "user@test.com", "User", UserRole.CONSUMER);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        when(userService.getById(userId)).thenReturn(user);
        when(jdbcTemplate.queryForObject(eq(
                "SELECT COUNT(*) FROM oauth2_authorization WHERE principal_name = ? " +
                "AND (access_token_expires_at > now() OR refresh_token_expires_at > now())"),
                eq(Integer.class), eq("user@test.com"))).thenReturn(2);

        var result = sessionController.sessionStatus(auth);

        assertNotNull(result);
        assertEquals(2L, result.getBody().get("activeSessions"));
    }
}
