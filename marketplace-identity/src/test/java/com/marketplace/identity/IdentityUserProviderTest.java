package com.marketplace.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdentityUserProviderTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private IdentityUserProvider userProvider;

    @Test
    void getCurrentUserId_returnsUserId() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("sub-1");

        UUID userId = UUID.randomUUID();
        User user = new User(userId, "sub-1", "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findBySubject("sub-1")).thenReturn(Optional.of(user));

        UUID result = userProvider.getCurrentUserId(token);

        assertEquals(userId, result);
    }

    @Test
    void getCurrentUserId_throwsWhenUserNotFound() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("missing-sub");
        when(userRepository.findBySubject("missing-sub")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> userProvider.getCurrentUserId(token));
    }

    @Test
    void getCurrentUserId_throwsForUnsupportedAuth() {
        Authentication auth = mock(Authentication.class);

        assertThrows(IllegalArgumentException.class, () -> userProvider.getCurrentUserId(auth));
    }

    @Test
    void isAdmin_returnsTrueForAdminRole() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getAuthorities()).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertTrue(userProvider.isAdmin(token));
    }

    @Test
    void isAdmin_returnsFalseForNonAdminRole() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getAuthorities()).thenReturn(List.of(new SimpleGrantedAuthority("ROLE_USER")));

        assertFalse(userProvider.isAdmin(token));
    }

    @Test
    void isAdmin_returnsFalseForNoRoles() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getAuthorities()).thenReturn(List.of());

        assertFalse(userProvider.isAdmin(token));
    }
}
