package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.EmailVerificationRequestedEvent;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * A-04 (official-compliance plan §6, wave A — A.2, email verification): the
 * activation of the dormant {@code email/welcome} template — the account's
 * birth journey now carries the verification the register javadoc declared
 * as its missing debt: a self-registered account is born HELD
 * ({@code enabled=false}, the framework's own account-state primitive —
 * DaoAuthenticationProvider refuses a disabled row, so no authorization
 * code or token can be minted for it), and this service's redemption leg
 * lifts the hold.
 *
 * <p><b>The state model (registered in the contracts ledger with the
 * V112 migration):</b> the account's verification state is its LATEST
 * EMAIL_VERIFICATION token row —
 * <ul>
 *   <li>no row → a pre-A-04 or externally-provisioned account (OIDC sync,
 *       the break-glass admin seed): grandfathered as verified, the hold
 *       never applied to it, resend refuses;</li>
 *   <li>a live (unconsumed) row → PENDING: register or resend issued it,
 *       redemption may lift the hold;</li>
 *   <li>a consumed row → FINISHED: either the owner completed verification
 *       (the hold was lifted) or an administrative surface consumed the
 *       outstanding right (disable/pseudonymize — the ban-vs-verification
 *       invariant: a banned account holds no redemption right, so this
 *       surface can never re-enable what an administrator shut).</li>
 * </ul></p>
 *
 * <p><b>The hold's honest login answer:</b> the login gate's own
 * DisabledException for a held account is the truth — the account EXISTS
 * but is not yet usable; the mail the user is holding tells them why. No
 * "unverified" message is invented at the gate (the framework's account
 * state carries no such vocabulary — inventing one outside the official
 * primitives is exactly what this system does not do).</p>
 *
 * <p>Both issuance sites (register's call, and {@link #resend(String)})
 * publish the {@link EmailVerificationRequestedEvent} cross-boundary event
 * inside their transaction; notifications renders the welcome mail with
 * the deep link. {@link #resend(String)} is enumeration-safe by the same
 * measured OWASP line as the reset request: one constant accepted answer
 * whether the address is pending, unknown, or already finished.</p>
 */
@Service
@Transactional
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    /** The deep-link route the client implements for the verification landing page. */
    static final String VERIFY_ROUTE = "/verify-email?token=";

    private final UserRepository userRepository;
    private final UserDetailsManager userDetailsManager;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthActionTokenService authActionTokenService;
    private final IdentityMailProperties properties;

    public EmailVerificationService(UserRepository userRepository,
                                    UserDetailsManager userDetailsManager,
                                    ApplicationEventPublisher eventPublisher,
                                    AuthActionTokenService authActionTokenService,
                                    IdentityMailProperties properties) {
        this.userRepository = userRepository;
        this.userDetailsManager = userDetailsManager;
        this.eventPublisher = eventPublisher;
        this.authActionTokenService = authActionTokenService;
        this.properties = properties;
    }

    /**
     * The issuance leg, shared by registration and the anonymous resend
     * surface: mints the one-time verification right for a PENDING account
     * and publishes the mail event. Package-private by design — the two
     * legitimate callers both live in this module (register's held birth,
     * {@link #resend(String)}); no other surface may arm a verification.
     *
     * @return whether the mail event was published (the caller never
     * reveals this — the resend contract is the constant 202)
     */
    @Observed(name = "email.verification.send")
    boolean issueFor(String subject) {
        Optional<User> user = userRepository.findBySubject(subject);
        if (user.isEmpty()) {
            return false;
        }
        return authActionTokenService
                .issue(subject, AuthActionTokenPurpose.EMAIL_VERIFICATION,
                        properties.emailVerificationTtl())
                .map(issued -> {
                    String verificationLink =
                            properties.mailBaseUrl() + VERIFY_ROUTE + issued.rawToken();
                    eventPublisher.publishEvent(new EmailVerificationRequestedEvent(
                            user.get().getId(), user.get().getDisplayName(),
                            verificationLink, issued.expiresAt()));
                    log.info("Email verification token issued: subject={}", subject);
                    return true;
                })
                .orElse(false);
    }

    /**
     * The anonymous resend surface for an account still awaiting
     * verification. The state rule above is the whole guard: only a PENDING
     * account (a live token row — expired or not) gets a new mail;
     * an unknown address, a grandfathered account, a finished one, and an
     * administratively consumed one all take the same silent path, and the
     * caller answers the constant 202 for every case (the OWASP
     * enumeration wall — measured line in PasswordResetService's javadoc).
     */
    @Observed(name = "email.verification.resend")
    public void resend(String email) {
        String subject = email.trim();
        if (!userDetailsManager.userExists(subject)) {
            log.info("Verification resend requested for an address with no login account — silent no-op");
            return;
        }
        // The state guard: re-arm ONLY a pending verification. A consumed
        // latest row means finished — the owner completed it, or an
        // administrative surface consumed the outstanding right (the
        // ban-vs-verification invariant); either way this surface must not
        // mint a fresh redemption right for it. No row at all means the
        // hold never applied (grandfathered) — nothing to resend.
        boolean pending = authActionTokenService
                .latest(subject, AuthActionTokenPurpose.EMAIL_VERIFICATION)
                .map(token -> !token.isConsumed())
                .orElse(false);
        if (pending) {
            issueFor(subject);
        } else {
            log.info("Verification resend skipped — the account is not pending: subject={}", subject);
        }
    }

    /**
     * The redemption leg: consumes the token (the single-use wall answers
     * the honest 400 for unknown/used/expired), then lifts the hold through
     * the official {@code updateUser} — every stored flag and the encoded
     * password replayed verbatim, only {@code enabled} flipping to true.
     * The consumption and the flip commit atomically; a concurrent
     * administrative disable loses its outstanding token first (the
     * invariant's consume happens in that surface's transaction), so the
     * two writers can never both commit against the same live token row.
     */
    @Observed(name = "email.verification.complete")
    public void completeVerification(String token) {
        AuthActionToken redeemed = authActionTokenService
                .consume(token, AuthActionTokenPurpose.EMAIL_VERIFICATION);
        String username = redeemed.getUsername();
        UserDetails stored = userDetailsManager.loadUserByUsername(username);
        userDetailsManager.updateUser(org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password(stored.getPassword())
                .authorities(stored.getAuthorities())
                .accountExpired(!stored.isAccountNonExpired())
                .accountLocked(!stored.isAccountNonLocked())
                .credentialsExpired(!stored.isCredentialsNonExpired())
                .disabled(false)
                .build());
        log.info("Email verification completed — the hold is lifted: username={}", username);
    }
}
