# Auto-injected credentials — do not modify
__AUTH_CREDENTIAL__ = ""
__AUTH_TYPE__ = "public"
__AUTH_HEADERS__ = {}
package com.marketplace.identity.internal;

import com.marketplace.identity.AuthAuditService;
import com.marketplace.identity.AuthEventType;
import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.UserDetailsManager;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DevDataInitializer}.
 *
 * <p>Verifies the security-critical aspects:
 * <ul>
 *   <li>Idempotency -- re-running does not duplicate the admin user or client</li>
 *   <li>PKCE is required on the seeded OAuth2 client (RFC 8252 section7.1)</li>
 *   <li>Both {@code auth_users} and domain {@code users} rows are created for admin</li>
 *   <li>Redirect URI honors the {@code OAUTH2_REDIRECT_URI} env var</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DevDataInitializerTest {

    @Mock private RegisteredClientRepository clientRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserDetailsManager userDetailsManager;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthAuditService auditService;

    private DevDataInitializer initializer;

    @BeforeEach
    void setUp() {
        // Constructor injection -- no reflection needed. Pass test config values directly.
        initializer = new DevDataInitializer(
                "test-admin-pass",
                "test-client-secret",
                "https://example.com/login/oauth2/code/marketplace-web-client",
                "https://example.com/");
    }

    @AfterEach
    void tearDown() {
        // No system properties set anymore -- constructor injection is clean.
    }

    @Test
    void seedDevData_createsAdminUserAndOAuth2Client() throws Exception {
        when(userRepository.findByEmail("admin")).thenReturn(Optional.empty());
        when(userDetailsManager.userExists("admin")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(clientRepository.findByClientId("marketplace-web-client")).thenReturn(null);

        initializer.seedDevData(clientRepository, userRepository, userDetailsManager, passwordEncoder, auditService)
                .run(new org.springframework.boot.DefaultApplicationArguments());

        // Verify admin user created in both auth_users (via UserDetailsManager) and users (via UserRepository).
        verify(userDetailsManager).createUser(argThat(u ->
                u.getUsername().equals("admin") && u.getPassword().equals("encoded")
                        && u.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))));
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertEquals("admin", savedUser.getEmail());
        assertEquals(UserRole.ADMIN, savedUser.getRole());
        verify(auditService).log(eq("admin"), eq(AuthEventType.REGISTRATION), any());

        // Verify OAuth2 client created with PKCE enabled.
        ArgumentCaptor<RegisteredClient> clientCaptor = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(clientRepository).save(clientCaptor.capture());
        RegisteredClient savedClient = clientCaptor.getValue();
        assertTrue(savedClient.getClientSettings().isRequireProofKey(),
                "PKCE must be required (RFC 8252 section7.1)");
        assertTrue(savedClient.getClientSettings().isRequireAuthorizationConsent());
        assertTrue(savedClient.getRedirectUris().stream()
                        .anyMatch(u -> u.toString().equals("https://example.com/login/oauth2/code/marketplace-web-client")),
                "redirect_uri must honor OAUTH2_REDIRECT_URI env var");
        assertTrue(savedClient.getScopes().stream().anyMatch(s -> s.equals("email")),
                "email scope must be present (OIDC Core section5.4)");
        // Refresh-token rotation
        assertFalse(savedClient.getTokenSettings().isReuseRefreshTokens());
    }

    @Test
    void seedDevData_skipsWhenAdminAlreadyExists() throws Exception {
        when(userRepository.findByEmail("admin")).thenReturn(Optional.of(mock(User.class)));

        initializer.seedDevData(clientRepository, userRepository, userDetailsManager, passwordEncoder, auditService)
                .run(new org.springframework.boot.DefaultApplicationArguments());

        verify(userDetailsManager, never()).createUser(any());
        verify(userRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any());
    }

    @Test
    void seedDevData_skipsWhenClientAlreadyExists() throws Exception {
        when(userRepository.findByEmail("admin")).thenReturn(Optional.empty());
        when(userDetailsManager.userExists("admin")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        when(clientRepository.findByClientId("marketplace-web-client"))
                .thenReturn(mock(RegisteredClient.class));

        initializer.seedDevData(clientRepository, userRepository, userDetailsManager, passwordEncoder, auditService)
                .run(new org.springframework.boot.DefaultApplicationArguments());

        verify(userDetailsManager).createUser(any());
        verify(clientRepository, never()).save(any());
    }
}