package com.marketplace.identity;

import com.marketplace.identity.dto.ResetPasswordRequest;
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
 * <p>Follows OWASP Forgot Password Cheat Sheet:
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

    public PasswordResetService(UserRepository userRepository,
                                 UserDetailsManager userDetailsManager,
                                 PasswordEncoder passwordEncoder,
                                 VerificationTokenService tokenService,
                                 ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.userDetailsManager = userDetailsManager;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Initiates password reset. Does not reveal if email exists.
     */
    @Transactional
    public void initiateReset(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return; // Silent return for security
        }

        User user = userOpt.get();
        VerificationToken token = tokenService.generateToken(user.getId(), VerificationTokenType.PASSWORD_RESET);

        eventPublisher.publishEvent(new PasswordResetRequestedEvent(user.getEmail(), token.getToken()));
    }

    /**
     * Resets password using a valid token.
     */
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
    }
}
