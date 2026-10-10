package com.marketplace.identity;

import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * A-05 (official-compliance plan §6 wave A — A.3, the self-service account
 * deletion; execution plan §6 unit A-05): the app-store requirement's
 * surface — "apps that support account creation must also offer account
 * deletion" — opened through the pseudonymization plan's own declared door
 * (gate b-5: {@code DELETE /api/v1/users/me} بمسار التحقق المزدوج, "يفتح
 * بابه عند أول طلب فعلي" — A.3 is that first actual request, the owner's
 * compliance-plan order).
 *
 * <p><b>The composition the governing documents prescribe, and nothing
 * beside them:</b> the account's own holder triggers the EXISTING GDPR
 * erasure machinery — {@link UserService#pseudonymizeAccount(UUID, String, String)},
 * the I7 §5-أ one-transaction operation (A.3's "Data JPA (GDPR purge قائم)"
 * — the existing purge, never a new one). No new stores, no new events, no
 * migration: the identifier replacement rides V46's {@code pseudonymized_at},
 * the login identity dies through the framework
 * {@link UserDetailsManager#deleteUser(String)}, the issued authorizations
 * die through the standing {@code DELETE_AUTHORIZATIONS_BY_PRINCIPAL} kill,
 * and the caches invalidate through the standing AFTER_COMMIT channel.</p>
 *
 * <p><b>The double verification (the plan's own words, "بمسار التحقق
 * المزدوج"):</b> the request must carry BOTH proofs — the live Bearer token
 * (the resource-server chain's {@code anyRequest().authenticated()} gate)
 * AND the account's current password. The verification is the official
 * primitive the login gate itself consults —
 * {@link PasswordEncoder#matches(CharSequence, String)} on the stored
 * verifier (the same {@code DaoAuthenticationProvider} contract, the
 * {@code DelegatingPasswordEncoder}'s stored form). A stolen-but-unexpired
 * access token (its documented 900s TTL window) alone can therefore never
 * destroy the owner's account: the irreversible operation demands the
 * credential the thief does not hold. A wrong password answers
 * {@link BadCredentialsException} — an {@code AuthenticationException} the
 * house advice maps to the standing 401 AUTHN-001 problem contract — and
 * NOTHING is mutated (the guard runs before any store is touched).</p>
 *
 * <p><b>The honest negatives, each measured against the existing
 * machinery:</b> an already-deleted account's token resolves to no live
 * subject row ({@code findBySubject} misses — the pseudonymized row carries
 * the derived replacement) and answers the honest 404; a projection row
 * without a login account (the L23 defensive shape) answers 404 the same
 * way {@code pseudonymizeAccount} itself would; the last-active-ADMIN
 * guard, the idempotence no-op, and the tombstone guard all apply unchanged
 * because the deletion IS the same operation — only the trigger moved from
 * the administrative surface to the account's own holder.</p>
 *
 * <p><b>The audit convention (register's own precedent):</b> the actor is
 * "self" — the account acting on itself, never the raw subject string in
 * the audit line (the standing CWE-532 discipline {@code pseudonymizeAccount}
 * documents); the reason is the fixed journey constant, not caller free
 * text (the administrative surface's {@code reason} field belongs to the
 * administrator's judgment — the self-service journey has exactly one
 * reason, and the client has nothing to narrate).</p>
 */
@Service
@Transactional
public class AccountSelfDeletionService {

    /** The fixed journey reason the structured audit line records. */
    static final String SELF_DELETION_REASON = "self-service account deletion (app-store requirement)";

    /** The audit actor — the account itself (register's precedent: the actor IS the account). */
    static final String SELF_ACTOR = "self";

    private final UserRepository userRepository;
    private final UserDetailsManager userDetailsManager;
    private final UserService userService;
    /**
     * The #392 module-slice shape (the UserService/PasswordResetService
     * precedent verbatim): the encoder bean lives in the shared security
     * infrastructure outside identity's slice — resolved lazily at
     * verification time; in the full application the bean is always the one
     * every surface uses.
     */
    private final ObjectProvider<PasswordEncoder> passwordEncoder;

    public AccountSelfDeletionService(UserRepository userRepository,
                                      UserDetailsManager userDetailsManager,
                                      UserService userService,
                                      ObjectProvider<PasswordEncoder> passwordEncoder) {
        this.userRepository = userRepository;
        this.userDetailsManager = userDetailsManager;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Deletes the requesting account — the double-verified self-service
     * trigger of the standing I7 erasure operation.
     *
     * <p><b>Sequence (one transaction):</b></p>
     * <ol>
     *   <li>Resolve the account by the token's subject — the requester's own
     *       proof of identity, never a caller-supplied identifier. A miss
     *       (an already-pseudonymized account, an absent row) answers the
     *       honest 404.</li>
     *   <li>Load the login row through the framework manager and verify the
     *       presented password against the stored verifier with the official
     *       {@link PasswordEncoder#matches} primitive. A miss answers 401
     *       AUTHN-001 and mutates nothing.</li>
     *   <li>Delegate to {@link UserService#pseudonymizeAccount} — the
     *       existing one-transaction erasure (last-ADMIN guard, idempotence,
     *       identifier replacement, login-identity death, authorization
     *       death, cache invalidation, structured audit line).</li>
     * </ol>
     *
     * @param subject     the Bearer token's subject — the account being
     *                    deleted, resolved from the authentication itself
     * @param rawPassword the account's current password — the second proof
     *                    (verification input only; nothing is stored)
     * @throws ResourceNotFoundException no live row for the token's subject
     *                                   (deleted already, or no projection)
     * @throws BadCredentialsException   the presented password does not
     *                                   match the stored verifier — 401
     *                                   AUTHN-001, zero mutation
     */
    @Observed(name = "user.self.deletion")
    public void deleteOwnAccount(String subject, String rawPassword) {
        User user = userRepository.findBySubject(subject)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No account for the authenticated subject: " + subject));

        UserDetails stored;
        try {
            stored = userDetailsManager.loadUserByUsername(subject);
        } catch (UsernameNotFoundException ex) {
            // The L23 defensive shape pseudonymizeAccount itself carries: the
            // projection row exists but no login account backs it — there is
            // nothing whose holder could be double-verified, and nothing a
            // self-service call may erase through this surface.
            throw new ResourceNotFoundException(
                    "No authentication account for user: " + user.getId());
        }

        // The double verification — the official primitive the login gate's
        // own DaoAuthenticationProvider consults. Runs BEFORE any mutation:
        // a wrong password leaves every store byte-identical.
        if (!passwordEncoder.getObject().matches(rawPassword, stored.getPassword())) {
            throw new BadCredentialsException("Password verification failed");
        }

        // The existing GDPR purge — A.3's "قائم": the identical operation the
        // administrative surface triggers, with the self-service journey's
        // fixed reason and the register-precedent actor ("self").
        userService.pseudonymizeAccount(user.getId(), SELF_DELETION_REASON, SELF_ACTOR);
    }
}
