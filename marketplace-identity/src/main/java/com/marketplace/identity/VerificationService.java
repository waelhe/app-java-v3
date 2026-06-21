package com.marketplace.identity;

import com.marketplace.shared.api.UserVerifiedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles email verification and password reset logic.
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html">OWASP Forgot Password Cheat Sheet</a>
 */
@Service
public class VerificationService {

    private final VerificationTokenService tokenService;
    private final UserDetailsManager userDetailsManager;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthAuditService auditService;

    public VerificationService(VerificationTokenService tokenService,
                                UserDetailsManager userDetailsManager,
                                UserRepository userRepository,
                                ApplicationEventPublisher eventPublisher,
                                AuthAuditService auditService) {
        this.tokenService = tokenService;
        this.userDetailsManager = userDetailsManager;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional
    public void verifyEmail(String tokenValue) {
        VerificationToken token = tokenService.validateToken(tokenValue, VerificationTokenType.EMAIL_VERIFICATION);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new com.marketplace.shared.api.ResourceNotFoundException("User not found"));

        UserDetails userDetails = userDetailsManager.loadUserByUsername(user.getEmail());
        org.springframework.security.core.userdetails.UserDetails updatedUser =
                org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                        .password(userDetails.getPassword())
                        .roles("CONSUMER")
                        .disabled(false)
                        .build();
        userDetailsManager.updateUser(updatedUser);

        tokenService.markAsUsed(token);

        auditService.log(user.getEmail(), AuthEventType.EMAIL_VERIFIED, "Email verified successfully");

        eventPublisher.publishEvent(new UserVerifiedEvent(user.getId(), user.getEmail()));
    }
}
