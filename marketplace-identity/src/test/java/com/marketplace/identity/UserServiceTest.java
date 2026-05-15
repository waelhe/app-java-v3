package com.marketplace.identity;

import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @InjectMocks
    private UserService userService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    private final UUID id = UUID.randomUUID();
    private final String subject = "sub-1";
    private final String email = "user@example.com";
    private final String name = "User Name";

    @Test
    void getById_returnsUser() {
        User user = User.create(subject, email, name, UserRole.CONSUMER);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        assertEquals(user, userService.getById(id));
    }

    @Test
    void getById_throwsWhenNotFound() {
        when(userRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> userService.getById(id));
    }

    @Test
    void getBySubject_returnsUser() {
        User user = User.create(subject, email, name, UserRole.CONSUMER);
        when(userRepository.findBySubject(subject)).thenReturn(Optional.of(user));
        assertEquals(user, userService.getBySubject(subject));
    }

    @Test
    void getBySubject_throwsWhenNotFound() {
        when(userRepository.findBySubject(subject)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> userService.getBySubject(subject));
    }

    @Test
    void findAll_returnsPage() {
        User user = User.create(subject, email, name, UserRole.CONSUMER);
        var pageable = PageRequest.of(0, 10);
        var page = new PageImpl<>(List.of(user));
        when(userRepository.findAll(pageable)).thenReturn(page);
        assertEquals(page, userService.findAll(pageable));
    }

    @Test
    void findAllSummaries_returnsSummaries() {
        User user = User.create(subject, email, name, UserRole.CONSUMER);
        var pageable = PageRequest.of(0, 10);
        var page = new PageImpl<>(List.of(user));
        when(userRepository.findAll(pageable)).thenReturn(page);
        Page<?> summaries = userService.findAllSummaries(pageable);
        assertEquals(1, summaries.getTotalElements());
    }

    @Test
    void syncFromOidc_createsNewUser() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(subject);
        when(jwt.getClaimAsString("email")).thenReturn(email);
        when(jwt.getClaimAsString("name")).thenReturn(name);
        when(jwt.getClaimAsStringList("roles")).thenReturn(null);
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of());
        when(userRepository.findBySubject(subject)).thenReturn(Optional.empty());
        User saved = User.create(subject, email, name, UserRole.CONSUMER);
        when(userRepository.save(any(User.class))).thenReturn(saved);
        assertNotNull(userService.syncFromOidc(token));
        verify(userRepository).save(any(User.class));
    }

    @Test
    void syncFromOidc_updatesExistingUser() {
        User existing = User.create(subject, email, name, UserRole.CONSUMER);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(subject);
        when(jwt.getClaimAsString("email")).thenReturn("new-email@example.com");
        when(jwt.getClaimAsString("name")).thenReturn("New Name");
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of());
        when(userRepository.findBySubject(subject)).thenReturn(Optional.of(existing));
        User result = userService.syncFromOidc(token);
        assertEquals("new-email@example.com", result.getEmail());
        assertEquals("New Name", result.getDisplayName());
        verify(userRepository, never()).save(any());
    }

    @Test
    void syncFromOidc_resolvesAdminRole() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("admin-sub");
        when(jwt.getClaimAsString("email")).thenReturn("admin@example.com");
        when(jwt.getClaimAsString("name")).thenReturn("Admin");
        when(jwt.getClaimAsStringList("roles")).thenReturn(List.of("ADMIN"));
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of());
        when(userRepository.findBySubject("admin-sub")).thenReturn(Optional.empty());
        when(userRepository.save(userCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));
        userService.syncFromOidc(token);
        assertEquals(UserRole.ADMIN, userCaptor.getValue().getRole());
    }

    @Test
    void syncFromOidc_resolvesProviderRole() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("provider-sub");
        when(jwt.getClaimAsString("email")).thenReturn("provider@example.com");
        when(jwt.getClaimAsString("name")).thenReturn("Provider");
        when(jwt.getClaimAsStringList("roles")).thenReturn(List.of("PROVIDER"));
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of());
        when(userRepository.findBySubject("provider-sub")).thenReturn(Optional.empty());
        when(userRepository.save(userCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));
        userService.syncFromOidc(token);
        assertEquals(UserRole.PROVIDER, userCaptor.getValue().getRole());
    }

    @Test
    void syncFromOidc_resolvesConsumerRole() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("consumer-sub");
        when(jwt.getClaimAsString("email")).thenReturn("consumer@example.com");
        when(jwt.getClaimAsString("name")).thenReturn("Consumer");
        when(jwt.getClaimAsStringList("roles")).thenReturn(List.of());
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of());
        when(userRepository.findBySubject("consumer-sub")).thenReturn(Optional.empty());
        when(userRepository.save(userCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));
        userService.syncFromOidc(token);
        assertEquals(UserRole.CONSUMER, userCaptor.getValue().getRole());
    }
}
