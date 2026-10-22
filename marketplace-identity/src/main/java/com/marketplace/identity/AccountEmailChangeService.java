package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * account email-change journey's owning use case — the Saleor
 * {@code requestEmailChange}/{@code confirmEmailChange} mutations.
 *
 * <p>The journey rides the standing one-time-token mechanism (V112, widened
 * by V251): the REQUEST leg password-verifies the caller (the official
 * {@code PasswordEncoder#matches} primitive — the AccountSelfDeletionService
 * precedent), records the pending pair on the account's own row, issues a
 * single-use TTL redemption right and publishes the mail event; the CONFIRM
 * leg consumes the right and moves BOTH stores of truth (the login username
 * via the framework {@code UserDetailsManager} create/delete pair, plus the
 * projection row's {@code users.email}) in one transaction. The new
 * address's availability is checked at CONFIRM time — the request leg stays
 * enumeration-safe (the PasswordResetService convention).</p>
 */
@Service
@Transactional
public class AccountEmailChangeService {

    private static final Logger log = LoggerFactory.getLogger(AccountEmailChangeService.class);

    static final Duration EMAIL_CHANGE_TTL = Duration.ofMinutes(30);
    private static final Pattern EMAIL_SHAPE =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository userRepository;
    private final UserDetailsManager userDetailsManager;
    private final AuthActionTokenService authActionTokenService;
    private final UserService userService;
    private final ObjectProvider<PasswordEncoder> encoder;
    private final ApplicationEventPublisher eventPublisher;

    public AccountEmailChangeService(UserRepository userRepository,
                                      UserDetailsManager userDetailsManager,
                                      AuthActionTokenService authActionTokenService,
                                      UserService userService,
                                      ObjectProvider<PasswordEncoder> encoder,
                                      ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.userDetailsManager = userDetailsManager;
        this.authActionTokenService = authActionTokenService;
        this.userService = userService;
        this.encoder = encoder;
        this.eventPublisher = eventPublisher;
    }

    /** The request leg — password-verified, pending pair recorded, right issued. */
    @Observed(name = "account.email_change.request")
    public void requestChange(Authentication caller, String newEmail, String rawPassword) {
        String subject = caller.getName();
        if (newEmail == null || !EMAIL_SHAPE.matcher(newEmail.trim()).matches()) {
            throw new IllegalArgumentException("A valid new email address is required");
        }
        // The case-insensitive account-identifier discipline (the CodeRabbit
        // integrity round): the admin path's requireEmail lowercases before
        // storing, so the self-service path must too — a mixed-case target
        // would otherwise store as-is, compare equal case-insensitively here,
        // and then MISS the case-sensitive existsBySubject/userExists lookups
        // at confirm time (two accounts for one address, logins that never
        // resolve).
        String target = newEmail.trim().toLowerCase(Locale.ROOT);
        if (target.equalsIgnoreCase(subject)) {
            throw new IllegalArgumentException("The new email address equals the current one");
        }
        UserDetails stored = requireLogin(subject);
        if (!encoder.getObject().matches(rawPassword, stored.getPassword())) {
            throw new BadCredentialsException("Password verification failed");
        }
        User user = userRepository.findBySubject(subject)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No account for the authenticated subject: " + subject));
        user.requestEmailChange(target);
        authActionTokenService.issue(subject, AuthActionTokenPurpose.EMAIL_CHANGE, EMAIL_CHANGE_TTL)
                .ifPresent(issued -> eventPublisher.publishEvent(new com.marketplace.shared.api
                        .EmailChangeRequestedEvent(user.getId(), subject, target,
                        issued.rawToken(), issued.expiresAt())));
        log.info("Email change requested: subject={}, expiresAt={}", subject,
                java.time.Instant.now().plus(EMAIL_CHANGE_TTL));
    }

    /** The confirm leg — consumes the right, moves both stores, answers the new email. */
    @Observed(name = "account.email_change.confirm")
    public String confirmChange(String token) {
        AuthActionToken redeemed = authActionTokenService
                .consume(token, AuthActionTokenPurpose.EMAIL_CHANGE);
        String oldSubject = redeemed.getUsername();
        User user = userRepository.findBySubject(oldSubject)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No account for the redeemed change right: " + oldSubject));
        String newEmail = user.consumePendingEmailChange();
        if (newEmail == null) {
            throw new ConflictException("No pending email change for the redeemed right");
        }
        if (userRepository.existsBySubject(newEmail) || userDetailsManager.userExists(newEmail)) {
            throw new ConflictException("The new email address already owns an account");
        }
        UserDetails stored = requireLogin(oldSubject);
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(newEmail)
                .password(stored.getPassword())
                .authorities(stored.getAuthorities())
                .accountExpired(!stored.isAccountNonExpired())
                .accountLocked(!stored.isAccountNonLocked())
                .credentialsExpired(!stored.isCredentialsNonExpired())
                .disabled(!stored.isEnabled())
                .build());
        // V112's FK leg (the CodeRabbit integrity round): EVERY token row
        // for the old subject — including the one this redemption just
        // consumed — still references auth_users(oldSubject), so the login
        // row cannot die until the history moves out of the way. The
        // entity-per-entity delete leaves an Envers DEL revision per row:
        // the redemption audit trail survives the move.
        authActionTokenService.purgeHistory(oldSubject);
        userDetailsManager.deleteUser(oldSubject);
        // The projection row's login identity moves TOGETHER (the
        // subject==username invariant): subject AND email in the same
        // write — moving only the email left the subject pointing at the
        // dead login name and broke every findBySubject lookup.
        user.changeLoginIdentity(newEmail);
        // The shared aftermath: the old principal's authorizations die
        // with their refresh grants, and the two user caches drop their
        // stale subject-keyed copies (the standing channels).
        userService.loginIdentityMoved(oldSubject);
        log.info("Email change confirmed: oldSubject={}, newEmail={}", oldSubject, newEmail);
        return newEmail;
    }

    private UserDetails requireLogin(String subject) {
        try {
            return userDetailsManager.loadUserByUsername(subject);
        } catch (UsernameNotFoundException ex) {
            throw new ResourceNotFoundException(
                    "No authentication account for the subject: " + subject);
        }
    }
}
