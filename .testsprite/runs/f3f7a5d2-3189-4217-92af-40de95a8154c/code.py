# Auto-injected credentials — do not modify
__AUTH_CREDENTIAL__ = ""
__AUTH_TYPE__ = "public"
__AUTH_HEADERS__ = {}
package com.marketplace.identity;

import com.marketplace.identity.dto.RegisterRequest;
import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link RegistrationService}.
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/user-details.html">Spring Security User Details</a>
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserDetailsManager userDetailsManager;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private VerificationTokenService tokenService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AuthAuditService auditService;

    @InjectMocks
    private RegistrationService registrationService;

    @Test
    void register_createsUserSuccessfully() {
        RegisterRequest request = new RegisterRequest("new@test.com", "SecurePass123", "Test User");
        when(userRepository.existsBySubject("new@test.com")).thenReturn(false);
        when(passwordEncoder.encode("SecurePass123")).thenReturn("encoded");
        when(tokenService.generateToken(any(UUID.class), eq(VerificationTokenType.EMAIL_VERIFICATION)))
                .thenReturn(VerificationToken.create(UUID.randomUUID(), "token",
                        VerificationTokenType.EMAIL_VERIFICATION,
                        java.time.Instant.now().plus(24, java.time.temporal.ChronoUnit.HOURS)));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User result = registrationService.register(request);

        assertNotNull(result);
        assertEquals("new@test.com", result.getEmail());
        assertEquals(UserRole.CONSUMER, result.getRole());
        verify(userDetailsManager).createUser(any());
        verify(userRepository).save(any());
        verify(tokenService).generateToken(any(), eq(VerificationTokenType.EMAIL_VERIFICATION));
        verify(eventPublisher).publishEvent(any(com.marketplace.shared.api.UserRegisteredEvent.class));
        verify(auditService).log(eq("new@test.com"), eq(AuthEventType.REGISTRATION), anyString());
    }

    @Test
    void register_throwsWhenEmailExists() {
        RegisterRequest request = new RegisterRequest("existing@test.com", "SecurePass123", "Test");
        when(userRepository.existsBySubject("existing@test.com")).thenReturn(true);

        assertThrows(ConflictException.class, () -> registrationService.register(request));
        verify(userDetailsManager, never()).createUser(any());
    }

    @Test
    void register_throwsWhenPasswordWeak() {
        RegisterRequest request = new RegisterRequest("weak@test.com", "weak", "Test");
        when(userRepository.existsBySubject("weak@test.com")).thenReturn(false);

        assertThrows(com.marketplace.shared.api.BadRequestException.class,
                () -> registrationService.register(request));
        verify(userDetailsManager, never()).createUser(any());
    }
}