package com.marketplace.identity;

import com.marketplace.identity.dto.ResetPasswordRequest;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PasswordResetRequestedEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Handles password reset flow.
 * <p>OWASP Forgot Password Cheat Sheet:
 * <ul>
 *   <li>Does not reveal if email exists (returns 204 silently)</li>
 *   <li>Uses time-bound, single-use tokens</li>
 *   <li>Invalidates token after use</li>
 * </ul>
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html">OWASP Forgot Password Cheat Sheet</a>
 */
@Service
public class PasswordResetService {

    private final UserRepository userRepository;
    private final UserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;
    private final VerificationTokenService tokenService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthAuditService auditService;

    public PasswordResetService(UserRepository userRepository,
                                 UserDetailsManager userDetailsManager,
                                 PasswordEncoder passwordEncoder,
                                 VerificationTokenService tokenService,
                                 ApplicationEventPublisher eventPublisher,
                                 AuthAuditService auditService) {
        this.userRepository = userRepository;
        this.userDetailsManager = userDetailsManager;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional
    public void initiateReset(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);

        // OWASP Forgot Password Cheat Sheet:
        // "Return a consistent message for both existent and non-existent accounts.
        //  Ensure that the time taken for the user response message is uniform."
        //
        // To achieve uniform timing, BOTH paths perform the same DB operations:
        //   1. tokenService.generateToken (DB INSERT)
        //   2. auditService.log (DB INSERT, REQUIRES_NEW)
        // The only difference is: existing users get an event published (email sent);
        // non-existent users do not (no email). The event publish is ~1ms — negligible
        // compared to the ~10ms DB writes that both paths perform.
        //
        // For non-existent users, a token is generated with a random UUID — the token
        // will never be validated (resetPassword does findById(userId) which returns
        // empty for a non-existent user → ResourceNotFoundException). The token expires
        // in 30 minutes and is harmless.
        //
        // Reference: https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            VerificationToken token = tokenService.generateToken(user.getId(), VerificationTokenType.PASSWORD_RESET);
            auditService.log(email, AuthEventType.PASSWORD_RESET_REQUESTED, "Password reset requested");
            eventPublisher.publishEvent(new PasswordResetRequestedEvent(user.getEmail(), token.getToken()));
        } else {
            // Non-existent user — perform the same DB operations to equalize timing.
            // Use a random UUID so the token references a non-existent user (can never be used).
            VerificationToken dummyToken = tokenService.generateToken(UUID.randomUUID(), VerificationTokenType.PASSWORD_RESET);
            auditService.log(email, AuthEventType.PASSWORD_RESET_REQUESTED, "Password reset requested for unknown email");
            // Do NOT publish event — no email sent for non-existent users.
        }
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        VerificationToken token = tokenService.validateToken(request.token(), VerificationTokenType.PASSWORD_RESET);

        PasswordValidator.validate(request.newPassword());

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        UserDetails userDetails = userDetailsManager.loadUserByUsername(user.getEmail());
        String newEncodedPassword = passwordEncoder.encode(request.newPassword());
        org.springframework.security.core.userdetails.UserDetails updatedUser =
                org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                        .password(newEncodedPassword)
                        .roles(user.getRole().name())
                        .disabled(!userDetails.isEnabled())
                        .build();
        userDetailsManager.updateUser(updatedUser);

        tokenService.markAsUsed(token);

        auditService.log(user.getEmail(), AuthEventType.PASSWORD_RESET_COMPLETED, "Password reset completed");
    }
}
