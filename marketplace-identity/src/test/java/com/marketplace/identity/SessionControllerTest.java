package com.marketplace.identity;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock private AuthAuditService auditService;
    @Mock private UserService userService;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private Authentication auth;

    @InjectMocks private SessionController sessionController;

    @Test
    void revokeAllSessions_deletesFromDb() {
        UUID userId = UUID.randomUUID();
        com.marketplace.identity.User user = com.marketplace.identity.User.create("sub", "user@test.com", "User", UserRole.CONSUMER);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        when(userService.getById(userId)).thenReturn(user);
        when(jdbcTemplate.update(eq("DELETE FROM oauth2_authorization WHERE principal_name = ?"), eq("user@test.com"))).thenReturn(3);

        sessionController.revokeAllSessions(auth);

        verify(jdbcTemplate).update(eq("DELETE FROM oauth2_authorization WHERE principal_name = ?"), eq("user@test.com"));
        verify(auditService).log(eq("user@test.com"), any(), any());
    }

    @Test
    void sessionStatus_returnsCount() {
        UUID userId = UUID.randomUUID();
        com.marketplace.identity.User user = com.marketplace.identity.User.create("sub", "user@test.com", "User", UserRole.CONSUMER);
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(userId);
        when(userService.getById(userId)).thenReturn(user);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM oauth2_authorization WHERE principal_name = ?"), eq(Integer.class), eq("user@test.com"))).thenReturn(2);

        var result = sessionController.sessionStatus(auth);

        assertNotNull(result);
        assertEquals(2L, result.getBody().get("activeSessions"));
    }
}
