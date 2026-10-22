package com.marketplace.identity;

import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * token-based account self-service legs — the Saleor
 * {@code accountRequestDeletion}/{@code accountDelete} and
 * {@code passwordChange} mutations' owning use case.
 *
 * <p>The deletion journey rides the V112 mechanism's ACCOUNT_DELETION
 * purpose (V251): the request issues a one-time erasure right to the
 * CALLER's own login and publishes the mail event; the redemption
 * delegates to the standing I7 erasure
 * ({@code UserService#pseudonymizeAccount}) — the same operation the
 * password-verified REST surface triggers, with the token as the single
 * proof instead.</p>
 *
 * <p>The password change: the caller's old password verified through the
 * official {@code PasswordEncoder#matches} primitive, the new secret
 * encoded by the same delegating encoder registration, the login row
 * updated through the framework {@code UserDetailsManager} replaying
 * every stored flag verbatim, and the account's issued authorizations
 * killed in the same transaction (the L23 refresh-resurrection kill every
 * secret-rotating surface applies).</p>
 */
@Service
@Transactional
public class AccountSelfServiceTokenService {

    private static final Logger log = LoggerFactory.getLogger(AccountSelfServiceTokenService.class);

    static final Duration DELETION_TTL = Duration.ofHours(24);
    private static final Pattern PASSWORD_SHAPE = Pattern.compile("^.{8,72}$");

    private final UserRepository userRepository;
    private final UserDetailsManager userDetailsManager;
    private final AuthActionTokenService authActionTokenService;
    private final UserService userService;
    private final ObjectProvider<PasswordEncoder> encoder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public AccountSelfServiceTokenService(UserRepository userRepository,
                                          UserDetailsManager userDetailsManager,
                                          AuthActionTokenService authActionTokenService,
                                          UserService userService,
                                          ObjectProvider<PasswordEncoder> encoder,
                                          org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                                          ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.userDetailsManager = userDetailsManager;
        this.authActionTokenService = authActionTokenService;
        this.userService = userService;
        this.encoder = encoder;
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    private static final String DELETE_AUTHORIZATIONS_BY_PRINCIPAL =
            "DELETE FROM oauth2_authorization WHERE principal_name = ?";

    /** {@code accountRequestDeletion}: the one-time erasure right, issued to the caller. */
    @Observed(name = "account.deletion.request")
    public void requestDeletion(Authentication caller) {
        String subject = caller.getName();
        User user = userRepository.findBySubject(subject)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No account for the authenticated subject: " + subject));
        authActionTokenService.issue(subject, AuthActionTokenPurpose.ACCOUNT_DELETION, DELETION_TTL)
                .ifPresent(issued -> eventPublisher.publishEvent(new com.marketplace.shared.api
                        .AccountDeletionRequestedEvent(user.getId(), user.getEmail(),
                        issued.rawToken(), issued.expiresAt())));
        log.info("Account deletion requested: subject={}, expiresAt={}", subject,
                java.time.Instant.now().plus(DELETION_TTL));
    }

    /** {@code accountDelete}: redeems the right through the standing I7 erasure. */
    @Observed(name = "account.deletion.execute")
    public void deleteByToken(String token) {
        AuthActionToken redeemed = authActionTokenService
                .consume(token, AuthActionTokenPurpose.ACCOUNT_DELETION);
        String subject = redeemed.getUsername();
        User user = userRepository.findBySubject(subject)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No live account for the redeemed deletion right: " + subject));
        userService.pseudonymizeAccount(user.getId(), "account self-deletion (token redemption)",
                "self");
    }

    /**
     * {@code passwordChange}: the caller's own secret rotation — old
     * verified, new encoded, login row updated, issued authorizations
     * killed (the L23 basis).
     */
    @Observed(name = "account.password.change")
    public void changePassword(Authentication caller, String oldPassword, String newPassword) {
        String subject = caller.getName();
        if (oldPassword == null) {
            throw new IllegalArgumentException("The current password is required to change it");
        }
        if (newPassword == null || !PASSWORD_SHAPE.matcher(newPassword).matches()) {
            throw new IllegalArgumentException(
                    "The new password must be 8 to 72 characters long");
        }
        UserDetails stored;
        try {
            stored = userDetailsManager.loadUserByUsername(subject);
        } catch (UsernameNotFoundException ex) {
            throw new ResourceNotFoundException(
                    "No authentication account for the subject: " + subject);
        }
        if (!encoder.getObject().matches(oldPassword, stored.getPassword())) {
            throw new BadCredentialsException("Password verification failed");
        }
        userDetailsManager.updateUser(org.springframework.security.core.userdetails.User
                .withUsername(subject)
                .password(encoder.getObject().encode(newPassword))
                .authorities(stored.getAuthorities())
                .accountExpired(!stored.isAccountNonExpired())
                .accountLocked(!stored.isAccountNonLocked())
                .credentialsExpired(!stored.isCredentialsNonExpired())
                .disabled(!stored.isEnabled())
                .build());
        jdbcTemplate.update(DELETE_AUTHORIZATIONS_BY_PRINCIPAL, subject);
        log.info("Account password changed and authorizations killed: subject={}", subject);
    }
}
