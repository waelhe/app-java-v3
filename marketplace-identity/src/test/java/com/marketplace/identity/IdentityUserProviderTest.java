package com.marketplace.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityUserProviderTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private IdentityUserProvider provider;

    @BeforeEach
    void setUp() {
        provider = new IdentityUserProvider(userRepository);
    }

    @Test
    void getCurrentUserIdReturnsUserId() {
        var id = UUID.randomUUID();
        var user = new User(id, "sub-1", "test@test.com", "Test", UserRole.CONSUMER);
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("sub-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claims(c -> c.putAll(Map.of("sub", "sub-1")))
                .build();
        var token = new JwtAuthenticationToken(jwt);
        when(userRepository.findBySubject("sub-1")).thenReturn(Optional.of(user));

        assertEquals(id, provider.getCurrentUserId(token));
    }

    @Test
    void getCurrentUserIdThrowsWhenSubjectNotFound() {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("unknown")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claims(c -> c.putAll(Map.of("sub", "unknown")))
                .build();
        var token = new JwtAuthenticationToken(jwt);
        when(userRepository.findBySubject("unknown")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> provider.getCurrentUserId(token));
    }

    @Test
    void getCurrentUserIdThrowsForUnsupportedAuth() {
        var auth = mock(Authentication.class);
        assertThrows(IllegalArgumentException.class, () -> provider.getCurrentUserId(auth));
    }

    @Test
    void isAdminReturnsTrueForAdminRole() {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("admin")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        var token = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertTrue(provider.isAdmin(token));
    }

    @Test
    void isAdminReturnsFalseForNonAdminRole() {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        var token = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertFalse(provider.isAdmin(token));
    }
}
