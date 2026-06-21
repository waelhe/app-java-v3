package com.marketplace.identity;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserVerifiedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {

    @Mock private VerificationTokenService tokenService;
    @Mock private UserDetailsManager userDetailsManager;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuthAuditService auditService;

    @InjectMocks private VerificationService verificationService;

    @Test
    void verifyEmail_success() {
        UUID userId = UUID.randomUUID();
        VerificationToken token = VerificationToken.create(userId, "token",
                VerificationTokenType.EMAIL_VERIFICATION, Instant.now().plus(1, ChronoUnit.HOURS));
        when(tokenService.validateToken("token", VerificationTokenType.EMAIL_VERIFICATION)).thenReturn(token);
        User user = User.create("sub", "user@test.com", "User", UserRole.CONSUMER);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        UserDetails mockDetails = org.springframework.security.core.userdetails.User.withUsername("user@test.com")
                .password("hashed").roles("CONSUMER").disabled(true).build();
        when(userDetailsManager.loadUserByUsername("user@test.com")).thenReturn(mockDetails);

        verificationService.verifyEmail("token");

        verify(userDetailsManager).updateUser(any());
        verify(tokenService).markAsUsed(token);
        verify(eventPublisher).publishEvent(any(UserVerifiedEvent.class));
        verify(auditService).log(eq("user@test.com"), eq(AuthEventType.EMAIL_VERIFIED), anyString());
    }

    @Test
    void verifyEmail_throwsWhenUserNotFound() {
        UUID userId = UUID.randomUUID();
        VerificationToken token = VerificationToken.create(userId, "token",
                VerificationTokenType.EMAIL_VERIFICATION, Instant.now().plus(1, ChronoUnit.HOURS));
        when(tokenService.validateToken("token", VerificationTokenType.EMAIL_VERIFICATION)).thenReturn(token);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> verificationService.verifyEmail("token"));
    }
}
