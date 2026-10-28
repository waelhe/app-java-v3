package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.EmailVerificationRequestedEvent;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    /**
     * row 8 (agent findings 2026-10-25, CWE-367): the account-level
     * serialization point — the login row's Postgres row lock. Every
     * administrative surface that shuts an account writes this row FIRST
     * ({@code JdbcUserDetailsManager.updateUser}/{@code deleteUser} — the
     * UPDATE/DELETE take the same lock), so a {@code FOR UPDATE} taken here
     * before any verification state is read makes the two writers mutually
     * exclusive on the account: whichever commits second re-reads the
     * committed truth of the first. The statement rides the caller's
     * transaction through the same transaction-scoped {@link JdbcTemplate}
     * discipline the {@code UserService} advisory locks use — the lock is
     * held until the surrounding transaction commits.
     */
    static final String LOCK_ACCOUNT_ROW =
            "select username from auth_users where username = ? for update";

    private final UserRepository userRepository;
    private final UserDetailsManager userDetailsManager;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthActionTokenService authActionTokenService;
    private final JdbcTemplate jdbcTemplate;
    private final IdentityMailProperties properties;

    public EmailVerificationService(UserRepository userRepository,
                                    UserDetailsManager userDetailsManager,
                                    ApplicationEventPublisher eventPublisher,
                                    AuthActionTokenService authActionTokenService,
                                    JdbcTemplate jdbcTemplate,
                                    IdentityMailProperties properties) {
        this.userRepository = userRepository;
        this.userDetailsManager = userDetailsManager;
        this.eventPublisher = eventPublisher;
        this.authActionTokenService = authActionTokenService;
        this.jdbcTemplate = jdbcTemplate;
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
     *
     * <p><b>row 8 (agent findings 2026-10-25, CWE-367) — the eligibility
     * read and the issuance run under the account's row lock.</b> The
     * prior shape read the pending state, then issued inside the same
     * transaction but WITHOUT the account-level serialization point: an
     * administrative disable committing between the read and the INSERT
     * consumed only the tokens already present, so the resend could mint
     * a fresh redemption right on a banned account — and redeeming it
     * lifted the administrative disable. Taking {@link #LOCK_ACCOUNT_ROW}
     * BEFORE the eligibility read closes every interleaving: a disable
     * that waits (its UPDATE blocks on this lock) proceeds to consume
     * whatever this transaction issued — {@code invalidateOutstanding}
     * runs under its own row lock, after this commit; a disable that
     * committed first leaves a consumed latest row, and this read declines.
     * No ordering can end with a live redemption right on a disabled
     * account. The registration birth needs no lock: the account row it
     * creates IS the serialization point (no administrative surface can
     * write a row that does not exist yet).</p>
     */
    @Observed(name = "email.verification.resend")
    public void resend(String email) {
        String subject = email.trim();
        if (!userDetailsManager.userExists(subject)) {
            log.info("Verification resend requested for an address with no login account — silent no-op");
            return;
        }
        // row 8: the serialization point precedes the eligibility read —
        // the decision and the issuance both happen under the lock.
        lockAccountRow(subject);
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
     *
     * <p><b>row 8 (agent findings 2026-10-25, CWE-367) — the account's row
     * lock precedes the consumption stamp.</b> The lock needs the account's
     * identity, which only the token carries, so the leg first resolves the
     * account through the non-consuming pre-read
     * ({@link AuthActionTokenService#accountOf}) and takes
     * {@link #LOCK_ACCOUNT_ROW} BEFORE consuming. The unlocked prior shape
     * had a window a disable could slip through: it commits between the
     * consume and the hold-lifting write, its {@code invalidateOutstanding}
     * finds the token already consumed (a no-op), and the redemption's
     * {@code updateUser} then re-enables what the administrator shut. Under
     * the lock the disable either waits — and applies its ban on top of the
     * completed redemption, the honest last-writer order — or has already
     * consumed the token, and the wall below answers the honest 400 with
     * the hold-lifting write never reached: <b>verification redemption
     * cannot remove a ban</b>. The finding's alternative (a separate
     * administrative-disable column) is unnecessary by the registered V112
     * state model: a LIVE token on the account implies no committed ban
     * (every administrative surface consumes the outstanding rights under
     * the same row lock), so the token lifecycle itself is the separate
     * state — no second enabled-like bit is invented outside the official
     * account model.</p>
     */
    @Observed(name = "email.verification.complete")
    public void completeVerification(String token) {
        String username = authActionTokenService
                .accountOf(token, AuthActionTokenPurpose.EMAIL_VERIFICATION)
                .orElseThrow(() -> new BadRequestException(
                        // the same construction the single-use wall's own
                        // unknown-token verdict uses — one message, one truth
                        "This " + AuthActionTokenPurpose.EMAIL_VERIFICATION.name()
                                .toLowerCase().replace('_', ' ')
                                + " link is invalid or has already been used"));
        lockAccountRow(username);
        AuthActionToken redeemed = authActionTokenService
                .consume(token, AuthActionTokenPurpose.EMAIL_VERIFICATION);
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

    /**
     * row 8: takes {@link #LOCK_ACCOUNT_ROW} on the caller's transaction.
     * An absent row locks nothing — the caller's own existence gate owns
     * that verdict (the V112 FK guarantees a token row's account exists).
     */
    private void lockAccountRow(String username) {
        List<String> locked = jdbcTemplate.queryForList(LOCK_ACCOUNT_ROW, String.class, username);
        if (locked.isEmpty() && log.isDebugEnabled()) {
            log.debug("Verification account lock found no login row: username={}", username);
        }
    }
}
