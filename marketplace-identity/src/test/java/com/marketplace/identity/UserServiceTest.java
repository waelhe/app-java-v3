package com.marketplace.identity;

import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository);
    }

    @Test
    void getByIdReturnsUser() {
        var id = UUID.randomUUID();
        var user = new User(id, "sub-1", "test@test.com", "Test", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        assertEquals(user, userService.getById(id));
    }

    @Test
    void getByIdThrowsWhenNotFound() {
        var id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.getById(id));
    }

    @Test
    void getBySubjectReturnsUser() {
        var user = new User(UUID.randomUUID(), "sub-1", "test@test.com", "Test", UserRole.CONSUMER);
        when(userRepository.findBySubject("sub-1")).thenReturn(Optional.of(user));

        assertEquals(user, userService.getBySubject("sub-1"));
    }

    @Test
    void getBySubjectThrowsWhenNotFound() {
        when(userRepository.findBySubject("unknown")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.getBySubject("unknown"));
    }

    @Test
    void findAllReturnsPage() {
        var pageable = PageRequest.of(0, 10);
        var user = new User(UUID.randomUUID(), "sub-1", "test@test.com", "Test", UserRole.CONSUMER);
        var page = new PageImpl<>(List.of(user));
        when(userRepository.findAll(pageable)).thenReturn(page);

        var result = userService.findAll(pageable);

        assertEquals(1, result.getContent().size());
        assertEquals(user, result.getContent().getFirst());
    }

    @Test
    void findAllSummariesReturnsPageOfSummaries() {
        var pageable = PageRequest.of(0, 10);
        var user = new User(UUID.randomUUID(), "sub-1", "test@test.com", "Test", UserRole.CONSUMER);
        var page = new PageImpl<>(List.of(user));
        when(userRepository.findAll(pageable)).thenReturn(page);

        var result = userService.findAllSummaries(pageable);

        assertEquals(1, result.getContent().size());
        assertEquals(user.getId(), result.getContent().getFirst().id());
        assertEquals(user.getEmail(), result.getContent().getFirst().email());
    }

    @Test
    void syncFromOidcCreatesNewUser() {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("new-sub")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claims(c -> c.putAll(Map.of("sub", "new-sub", "email", "new@test.com", "name", "New User")))
                .build();
        var token = new JwtAuthenticationToken(jwt);
        when(userRepository.findBySubject("new-sub")).thenReturn(Optional.empty());
        var savedUser = new User(UUID.randomUUID(), "new-sub", "new@test.com", "New User", UserRole.CONSUMER);
        when(userRepository.save(any())).thenReturn(savedUser);

        var result = userService.syncFromOidc(token);

        assertNotNull(result);
        verify(userRepository).save(any());
    }

    @Test
    void syncFromOidcUpdatesExistingUser() {
        var id = UUID.randomUUID();
        var existing = new User(id, "existing-sub", "old@test.com", "Old Name", UserRole.CONSUMER);
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("existing-sub")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claims(c -> c.putAll(Map.of("sub", "existing-sub", "email", "new@test.com", "name", "New Name")))
                .build();
        var token = new JwtAuthenticationToken(jwt);
        when(userRepository.findBySubject("existing-sub")).thenReturn(Optional.of(existing));

        var result = userService.syncFromOidc(token);

        assertEquals("new@test.com", result.getEmail());
        assertEquals("New Name", result.getDisplayName());
        verify(userRepository, never()).save(any());
    }

    @Test
    void syncFromOidcResolvesAdminRole() {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("admin-sub")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claims(c -> c.putAll(Map.of("sub", "admin-sub", "roles", List.of("ADMIN"))))
                .build();
        var token = new JwtAuthenticationToken(jwt);
        when(userRepository.findBySubject("admin-sub")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = userService.syncFromOidc(token);

        assertEquals(UserRole.ADMIN, result.getRole());
    }

    @Test
    void syncFromOidcResolvesProviderRole() {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("prov-sub")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claims(c -> c.putAll(Map.of("sub", "prov-sub", "roles", List.of("PROVIDER"))))
                .build();
        var token = new JwtAuthenticationToken(jwt);
        when(userRepository.findBySubject("prov-sub")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = userService.syncFromOidc(token);

        assertEquals(UserRole.PROVIDER, result.getRole());
    }

    @Test
    void syncFromOidcDefaultsToConsumerRole() {
        var jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("consumer-sub")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claims(c -> c.putAll(Map.of("sub", "consumer-sub")))
                .build();
        var token = new JwtAuthenticationToken(jwt);
        when(userRepository.findBySubject("consumer-sub")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = userService.syncFromOidc(token);

        assertEquals(UserRole.CONSUMER, result.getRole());
    }
}
