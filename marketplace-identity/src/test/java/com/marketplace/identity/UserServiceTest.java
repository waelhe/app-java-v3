package com.marketplace.identity;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
// removed -- conflicts with com.marketplace.identity.User
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.provisioning.UserDetailsManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link UserService}.
 * <p>Follows Spring Security testing patterns:
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/test/index.html">Spring Security Test</a>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserDetailsManager userDetailsManager;

    @Mock
    private AuthAuditService auditService;

    @InjectMocks
    private UserService userService;

    @Test
    void getById_returnsUser() {
        UUID id = UUID.randomUUID();
        User user = User.create("sub-1", "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        User result = userService.getById(id);

        assertEquals(user, result);
    }

    @Test
    void getById_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.getById(id));
    }

    @Test
    void getBySubject_returnsUser() {
        String subject = "sub-1";
        User user = User.create(subject, "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findBySubject(subject)).thenReturn(Optional.of(user));

        User result = userService.getBySubject(subject);

        assertEquals(user, result);
    }

    @Test
    void getBySubject_throwsWhenNotFound() {
        String subject = "sub-missing";
        when(userRepository.findBySubject(subject)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.getBySubject(subject));
    }

    @Test
    void findAll_returnsPage() {
        PageRequest pageable = PageRequest.of(0, 10);
        User user = User.create("sub-1", "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user)));

        Page<User> result = userService.findAll(pageable);

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void findAllSummaries_returnsPage() {
        PageRequest pageable = PageRequest.of(0, 10);
        UUID id = UUID.randomUUID();
        User user = new User(id, "sub-1", "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(user)));

        Page<UserSummary> result = userService.findAllSummaries(pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(id, result.getContent().getFirst().id());
    }

    @Test
    void syncFromOidc_createsNewUser() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("new-sub");
        when(jwt.getClaimAsString("email")).thenReturn("new@b.com");
        when(jwt.getClaimAsString("name")).thenReturn("New User");
        when(jwt.getClaimAsStringList("roles")).thenReturn(null);
        when(userRepository.findBySubject("new-sub")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User result = userService.syncFromOidc(token);

        assertEquals("new-sub", result.getSubject());
        assertEquals(UserRole.CONSUMER, result.getRole());
        verify(userRepository).save(any());
    }

    @Test
    void syncFromOidc_updatesExistingUser() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("existing-sub");
        when(jwt.getClaimAsString("email")).thenReturn("updated@b.com");
        when(jwt.getClaimAsString("name")).thenReturn("Updated Name");

        User existing = User.create("existing-sub", "old@b.com", "Old Name", UserRole.CONSUMER);
        when(userRepository.findBySubject("existing-sub")).thenReturn(Optional.of(existing));

        User result = userService.syncFromOidc(token);

        assertEquals("updated@b.com", result.getEmail());
        verify(userRepository, never()).save(any());
    }

    @Test
    void syncFromOidc_resolvesAdminRole() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("admin-sub");
        when(jwt.getClaimAsString("email")).thenReturn("admin@b.com");
        when(jwt.getClaimAsString("name")).thenReturn("Admin");
        when(jwt.getClaimAsStringList("roles")).thenReturn(List.of("ADMIN"));
        when(userRepository.findBySubject("admin-sub")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User result = userService.syncFromOidc(token);

        assertEquals(UserRole.ADMIN, result.getRole());
    }

    @Test
    void updateUserRole_changesRole() {
        UUID id = UUID.randomUUID();
        User user = User.create("sub-1", "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        UserDetails mockDetails = org.springframework.security.core.userdetails.User.withUsername("a@b.com")
                .password("hashed").roles("CONSUMER").disabled(false).build();
        when(userDetailsManager.loadUserByUsername("a@b.com")).thenReturn(mockDetails);

        userService.updateUserRole(id, "ADMIN");

        assertEquals(UserRole.ADMIN, user.getRole());
        verify(userDetailsManager).updateUser(any());
    }

    @Test
    void updateUserRole_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.updateUserRole(id, "ADMIN"));
    }

    @Test
    void provisionUser_createsNewUser() {
        String provider = "google";
        String providerId = "12345";
        when(userRepository.findBySubject("google:12345")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UUID result = userService.provisionUser(provider, providerId, "user@test.com", "Test User");

        assertNotNull(result);
        verify(userRepository).save(any());
    }

    @Test
    void provisionUser_updatesExistingUser() {
        String provider = "google";
        String providerId = "12345";
        User existing = User.create("google:12345", "old@test.com", "Old Name", UserRole.CONSUMER);
        when(userRepository.findBySubject("google:12345")).thenReturn(Optional.of(existing));

        UUID result = userService.provisionUser(provider, providerId, "new@test.com", "New Name");

        assertEquals(existing.getId(), result);
        assertEquals("new@test.com", existing.getEmail());
        verify(userRepository, never()).save(any());
    }

    @Test
    void suspendUser_disablesAccount() {
        UUID id = UUID.randomUUID();
        User user = User.create("sub-1", "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        UserDetails mockDetails = org.springframework.security.core.userdetails.User.withUsername("a@b.com")
                .password("hashed").roles("CONSUMER").disabled(false).build();
        when(userDetailsManager.loadUserByUsername("a@b.com")).thenReturn(mockDetails);

        userService.suspendUser(id);

        verify(userDetailsManager).updateUser(any());
    }

    @Test
    void reactivateUser_enablesAccount() {
        UUID id = UUID.randomUUID();
        User user = User.create("sub-1", "a@b.com", "Alice", UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        UserDetails mockDetails = org.springframework.security.core.userdetails.User.withUsername("a@b.com")
                .password("hashed").roles("CONSUMER").disabled(true).build();
        when(userDetailsManager.loadUserByUsername("a@b.com")).thenReturn(mockDetails);

        userService.reactivateUser(id);

        verify(userDetailsManager).updateUser(any());
    }
}
