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
        if (userOpt.isEmpty()) {
            return; // Silent return for security (OWASP)
        }

        User user = userOpt.get();
        VerificationToken token = tokenService.generateToken(user.getId(), VerificationTokenType.PASSWORD_RESET);

        auditService.log(email, AuthEventType.PASSWORD_RESET_REQUESTED, "Password reset requested");

        eventPublisher.publishEvent(new PasswordResetRequestedEvent(user.getEmail(), token.getToken()));
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
                        .roles(user.getRole().name().replace("ROLE_", ""))
                        .disabled(!userDetails.isEnabled())
                        .build();
        userDetailsManager.updateUser(updatedUser);

        tokenService.markAsUsed(token);

        auditService.log(user.getEmail(), AuthEventType.PASSWORD_RESET_COMPLETED, "Password reset completed");
    }
}
