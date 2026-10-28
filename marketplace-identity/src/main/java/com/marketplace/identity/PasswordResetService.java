package com.marketplace.identity;

import com.marketplace.shared.api.AccountCredentialsRotated;
import com.marketplace.shared.api.PasswordResetRequestedEvent;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A-04 (official-compliance plan §6, wave A — A.1, the password-reset
 * journey): the request and redemption legs of the official flow the plan
 * names Boot {@code reference/io/email.html} and Spring Security
 * {@code features/authentication/password-storage.html} for.
 *
 * <p><b>The official primitives, and nothing beside them:</b> the mail leg
 * is the house email channel (identity publishes the
 * {@link PasswordResetRequestedEvent} cross-boundary event inside this
 * transaction; notifications renders the activated dormant
 * {@code email/password-reset} template through the platform-infra
 * {@code EmailService} — the measured Boot stack of JavaMailSender +
 * Thymeleaf, fetched to {@code scripts/official-docs-fetch/boot-email.txt});
 * the NEW secret at redemption is encoded by the SAME delegating encoder
 * registration already uses (the PasswordEncoderFactories default the
 * Security reference documents — "{bcrypt}..." — never a raw store), and
 * the login store write goes through the framework
 * {@link UserDetailsManager#updateUser} replaying every stored flag
 * verbatim (the L23 builder shape: password swapped, enabled/authorities/
 * account flags preserved).</p>
 *
 * <p><b>Enumeration safety (the measured OWASP line):</b> "Ensure that
 * responses return in a consistent amount of time to prevent an attacker
 * enumerating which accounts exist" — {@link #requestReset(String)} answers
 * the SAME accepted result whether the address owns an account, holds no
 * login row (an OIDC-only account — no password to reset), or was
 * throttled; only the mail leg differs, and it is invisible to the caller.
 * The request never confirms or denies an account's existence.</p>
 *
 * <p><b>The redemption's security consequences, both measured house
 * facts:</b> the new password lands through the official manager; and every
 * issued authorization for the account dies with the change
 * ({@code DELETE_AUTHORIZATIONS_BY_PRINCIPAL} — the same L23-documented SAS
 * basis the role/status/pseudonymize surfaces use: a stolen refresh token
 * must not survive the owner rotating the secret it guards).</p>
 */
@Service
@Transactional
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /** The deep-link route the client implements for the reset landing page. */
    static final String RESET_ROUTE = "/reset-password?token=";

    /** Authorization rows in the SAS store (V13) are keyed by the principal
     * name — the same measured kill the role/status/pseudonymize surfaces
     * use (UserService's own constant, restated here because this service
     * owns its leg of the same invariant). */
    static final String DELETE_AUTHORIZATIONS_BY_PRINCIPAL =
            "DELETE FROM oauth2_authorization WHERE principal_name = ?";

    /**
     * Row 46 (agent findings 2026-10-26 — CWE-362): the credential write
     * is the PASSWORD COLUMN ONLY. The pre-fix flow replayed a full
     * {@code JdbcUserDetailsManager.updateUser} from a snapshot loaded
     * before the encoding — the framework's own order deletes the
     * CURRENT {@code auth_authorities} rows and re-inserts the snapshot's,
     * so an administrator's role removal landing in that window was
     * silently restored and the removed authority kept answering at the
     * next login. The password-only UPDATE leaves the authority rows and
     * the status flags exactly as their own admin surfaces wrote them
     * (each under the account row lock — row 8's serialization), so reset
     * rotates the credential and nothing else. The official
     * {@code UserDetailsPasswordService.updatePassword} is measured (7.1.2
     * bytecode) to delegate to the SAME full {@code updateUser} replace,
     * so the framework offers no password-only path — the explicit column
     * write on the SAME configured table is the surgical form.
     */
    static final String UPDATE_PASSWORD_ONLY =
            "UPDATE auth_users SET password = ? WHERE username = ?";

    /**
     * Row 20 (agent findings 2026-10-26 14:33 export, CWE-521): the shared
     * password policy the {@code register} flow pins — 8..72 characters
     * (the bcrypt byte ceiling). A record of the owning service's own
     * contract, so the redemption leg enforces it before consuming the
     * single-use token regardless of which transport carried the request.
     */
    private static final java.util.regex.Pattern PASSWORD_SHAPE =
            java.util.regex.Pattern.compile("^.{8,72}$");

    private final UserRepository userRepository;
    private final UserDetailsManager userDetailsManager;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthActionTokenService authActionTokenService;
    private final IdentityMailProperties properties;
    private final JdbcTemplate jdbcTemplate;
    /**
     * The #392 module-slice shape (the UserService precedent verbatim): the
     * encoder bean lives in the shared security infrastructure outside
     * identity's slice — resolved lazily at redemption time; in the full
     * application the bean is always the one every surface uses.
     */
    private final ObjectProvider<PasswordEncoder> passwordEncoder;

    public PasswordResetService(UserRepository userRepository,
                                UserDetailsManager userDetailsManager,
                                ApplicationEventPublisher eventPublisher,
                                AuthActionTokenService authActionTokenService,
                                IdentityMailProperties properties,
                                JdbcTemplate jdbcTemplate,
                                ObjectProvider<PasswordEncoder> passwordEncoder) {
        this.userRepository = userRepository;
        this.userDetailsManager = userDetailsManager;
        this.eventPublisher = eventPublisher;
        this.authActionTokenService = authActionTokenService;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * The request leg: issues a single-use, time-limited token for the
     * account the address owns and publishes the mail event — or does
     * nothing, silently, for every other case. The response contract is
     * the caller's constant 202 either way (the enumeration-safe wall).
     *
     * <p>Only a NATIVE login row is resettable: {@code userDetailsManager.
     * userExists(subject)} is the login store's own truth — an OIDC-only
     * account (no {@code auth_users} row, its password lives with the
     * identity provider) and an unknown address take the same silent
     * no-op path. The domain row supplies the mail's routing (userId) and
     * greeting (displayName); its absence with a live login row is the
     * register flow's single-transaction impossibility — treated as a
     * drift no-op and logged, never a 500 probe surface.</p>
     */
    @Observed(name = "password.reset.request")
    public void requestReset(String email) {
        String subject = email.trim();
        if (!userDetailsManager.userExists(subject)) {
            log.info("Password reset requested for an address with no login account — silent no-op");
            return;
        }
        userRepository.findBySubject(subject).ifPresent(user -> {
            authActionTokenService
                    .issue(subject, AuthActionTokenPurpose.PASSWORD_RESET,
                            properties.passwordResetTtl())
                    .ifPresent(issued -> {
                        String resetLink = properties.mailBaseUrl() + RESET_ROUTE + issued.rawToken();
                        eventPublisher.publishEvent(new PasswordResetRequestedEvent(
                                user.getId(), user.getDisplayName(), resetLink, issued.expiresAt()));
                        log.info("Password reset token issued: subject={}", subject);
                    });
        });
    }

    /**
     * The redemption leg: consumes the token (the single-use wall —
     * {@link AuthActionTokenService#consume} answers the honest 400 for an
     * unknown, already-used, or expired token), re-encodes the new secret
     * through the delegating encoder, writes the PASSWORD COLUMN alone
     * (the {@code UPDATE_PASSWORD_ONLY} shape — never a flag replay),
     * and kills the account's issued authorizations. The consumption, the
     * password write, and the authorization purge commit atomically — a
     * failure rolls the token back to live (the honest retry contract:
     * nothing half-spent).
     *
     * <p>Row 20 (agent findings 2026-10-26 14:33 export, CWE-521): the
     * shared password policy is enforced HERE — inside the owning use
     * case, before the token is consumed — not only at whichever transport
     * happened to check it. The GraphQL {@code setPassword} transport
     * checks for blank input alone, so a replacement shorter than the
     * minimum every other account flow enforces could previously be
     * written through this path; the policy is a fact about the credential,
     * so the credential's owning service owns the check.</p>
     *
     * @param token       the raw one-time token from the email's deep link
     * @param newPassword the CLEAR replacement — policy-validated here
     *                    (8..72, the bcrypt byte ceiling, the same shape
     *                    {@code register} pins) and encoded after
     */
    @Observed(name = "password.reset.complete")
    public void completeReset(String token, String newPassword) {
        if (newPassword == null || !PASSWORD_SHAPE.matcher(newPassword).matches()) {
            throw new IllegalArgumentException(
                    "The new password must be 8 to 72 characters long");
        }
        AuthActionToken redeemed =
                authActionTokenService.consume(token, AuthActionTokenPurpose.PASSWORD_RESET);
        String username = redeemed.getUsername();
        // The account's existence gate — the honest 404-shape failure when
        // the token's account is gone (the consumption rolls back with the
        // transaction: nothing half-spent, the honest retry contract).
        userDetailsManager.loadUserByUsername(username);
        // Row 46 (agent findings 2026-10-26): the PASSWORD COLUMN ONLY — the
        // authority and status snapshot loaded above is read for the
        // existence gate and DISCARDED, never replayed: an admin's role
        // removal or status change landing anywhere around the encoding
        // stays exactly as its own surface wrote it (the pre-fix
        // updateUser replay could restore a removed authority).
        jdbcTemplate.update(UPDATE_PASSWORD_ONLY,
                passwordEncoder.getObject().encode(newPassword), username);
        // Issued authorizations die with the change — the same measured SAS
        // kill the role/status/pseudonymize surfaces run: a refresh token
        // minted under the OLD password must not survive the owner rotating
        // the secret it guards.
        jdbcTemplate.update(DELETE_AUTHORIZATIONS_BY_PRINCIPAL, username);
        // The surviving-session kill (CodeRabbit agent findings, row 14 /
        // CWE-613): a live browser session holds its cached principal across
        // this credential write — the pre-reset cookie would re-mint through
        // the authorization-server chain exactly the grants this recovery
        // just revoked. The house R8 channel: the rotation fact published in
        // THIS transaction, consumed AFTER_COMMIT by
        // AccountStatusSessionInvalidator — every session of the account
        // expires with the commit, the owner re-authenticates with the new
        // credential, and the framework's resubmission covers a failed
        // expiry.
        eventPublisher.publishEvent(new AccountCredentialsRotated(username));
        log.info("Password reset completed: username={}", username);
    }
}
