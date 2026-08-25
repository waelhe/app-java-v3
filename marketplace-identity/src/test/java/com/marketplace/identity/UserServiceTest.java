package com.marketplace.identity;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

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
        assertEquals("Alice", result.getContent().getFirst().displayName());
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
        assertEquals("new@b.com", result.getEmail());
        assertEquals("New User", result.getDisplayName());
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
        assertEquals("Updated Name", result.getDisplayName());
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

        userService.updateUserRole(id, "ADMIN");

        assertEquals(UserRole.ADMIN, user.getRole());
        verify(userRepository).findById(id);
    }

    @Test
    void updateUserRole_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> userService.updateUserRole(id, "ADMIN"));
    }

    @Test
    void syncFromOidc_resolvesProviderRole() {
        Jwt jwt = mock(Jwt.class);
        JwtAuthenticationToken token = mock(JwtAuthenticationToken.class);
        when(token.getToken()).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("provider-sub");
        when(jwt.getClaimAsString("email")).thenReturn("provider@b.com");
        when(jwt.getClaimAsString("name")).thenReturn("Provider");
        when(jwt.getClaimAsStringList("roles")).thenReturn(List.of("PROVIDER"));
        when(userRepository.findBySubject("provider-sub")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User result = userService.syncFromOidc(token);

        assertEquals(UserRole.PROVIDER, result.getRole());
    }
}
