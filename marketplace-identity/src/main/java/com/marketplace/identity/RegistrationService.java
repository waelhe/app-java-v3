package com.marketplace.identity;

import com.marketplace.identity.dto.RegisterRequest;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.UserRegisteredEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles user registration logic.
 * <p>Uses Spring Security's {@link UserDetailsManager} for auth_users management
 * and {@link PasswordEncoder} for secure password hashing (BCrypt).
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/user-details.html">Spring Security User Details</a>
 */
@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final UserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;
    private final VerificationTokenService tokenService;
    private final ApplicationEventPublisher eventPublisher;

    public RegistrationService(UserRepository userRepository,
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
     * Registers a new consumer user.
     * <p>Flow:
     * 1. Validate email uniqueness
     * 2. Validate password strength (OWASP)
     * 3. Create auth_users entry (disabled until email verification)
     * 4. Create users entry (business entity)
     * 5. Generate verification token
     * 6. Publish event for notification
     */
    @Transactional
    public User register(RegisterRequest request) {
        if (userRepository.existsBySubject(request.email())) {
            throw new ConflictException("Email already registered");
        }

        PasswordValidator.validate(request.password());

        String encodedPassword = passwordEncoder.encode(request.password());
        org.springframework.security.core.userdetails.UserDetails userDetails =
                org.springframework.security.core.userdetails.User.withUsername(request.email())
                        .password(encodedPassword)
                        .roles("CONSUMER")
                        .disabled(true)
                        .build();
        userDetailsManager.createUser(userDetails);

        User user = User.create(request.email(), request.email(), request.displayName(), UserRole.CONSUMER);
        userRepository.save(user);

        VerificationToken token = tokenService.generateToken(user.getId(), VerificationTokenType.EMAIL_VERIFICATION);

        eventPublisher.publishEvent(new UserRegisteredEvent(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                token.getToken()
        ));

        return user;
    }
}
