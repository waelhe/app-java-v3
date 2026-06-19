package com.marketplace.identity;

import com.marketplace.identity.dto.ResetPasswordRequest;
import com.marketplace.shared.api.PasswordResetRequestedEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserDetailsManager userDetailsManager;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private VerificationTokenService tokenService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuthAuditService auditService;

    @InjectMocks private PasswordResetService passwordResetService;

    @Test
    void initiateReset_auditOnlyWhenEmailNotFound() {
        // OWASP: "Return a consistent message for both existent and non-existent accounts."
        // For non-existent users, audit log is written but NO token is generated
        // (FK constraint on verification_tokens.user_id → users.id rejects random UUIDs).
        when(userRepository.findByEmail("unknown@test.com")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> passwordResetService.initiateReset("unknown@test.com"));
        // Token is NOT generated — no FK violation.
        verify(tokenService, never()).generateToken(any(), any());
        // Audit log IS written — same as existing-user path.
        verify(auditService).log(eq("unknown@test.com"), eq(AuthEventType.PASSWORD_RESET_REQUESTED), anyString());
        // Event is NOT published — no email sent for non-existent users.
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void initiateReset_generatesTokenAndPublishesEvent() {
        User user = User.create("sub", "user@test.com", "User", UserRole.CONSUMER);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        VerificationToken token = VerificationToken.create(user.getId(), "token",
                VerificationTokenType.PASSWORD_RESET, Instant.now().plus(30, ChronoUnit.MINUTES));
        when(tokenService.generateToken(user.getId(), VerificationTokenType.PASSWORD_RESET)).thenReturn(token);

        passwordResetService.initiateReset("user@test.com");

        verify(eventPublisher).publishEvent(any(PasswordResetRequestedEvent.class));
        verify(auditService).log(eq("user@test.com"), eq(AuthEventType.PASSWORD_RESET_REQUESTED), anyString());
    }

    @Test
    void resetPassword_success() {
        UUID userId = UUID.randomUUID();
        VerificationToken token = VerificationToken.create(userId, "token",
                VerificationTokenType.PASSWORD_RESET, Instant.now().plus(30, ChronoUnit.MINUTES));
        when(tokenService.validateToken("token", VerificationTokenType.PASSWORD_RESET)).thenReturn(token);
        User user = User.create("sub", "user@test.com", "User", UserRole.CONSUMER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        UserDetails mockDetails = org.springframework.security.core.userdetails.User.withUsername("user@test.com")
                .password("old").roles("CONSUMER").disabled(false).build();
        when(userDetailsManager.loadUserByUsername("user@test.com")).thenReturn(mockDetails);
        when(passwordEncoder.encode("NewPass123")).thenReturn("encoded");

        passwordResetService.resetPassword(new ResetPasswordRequest("token", "NewPass123"));

        verify(userDetailsManager).updateUser(any());
        verify(tokenService).markAsUsed(token);
        verify(auditService).log(eq("user@test.com"), eq(AuthEventType.PASSWORD_RESET_COMPLETED), anyString());
    }

    @Test
    void resetPassword_throwsWhenUserNotFound() {
        UUID userId = UUID.randomUUID();
        VerificationToken token = VerificationToken.create(userId, "token",
                VerificationTokenType.PASSWORD_RESET, Instant.now().plus(30, ChronoUnit.MINUTES));
        when(tokenService.validateToken("token", VerificationTokenType.PASSWORD_RESET)).thenReturn(token);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> passwordResetService.resetPassword(new ResetPasswordRequest("token", "NewPass123")));
    }
}
