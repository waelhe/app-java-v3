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
import com.marketplace.shared.api.BadRequestException;
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
        // Row 17 (agent findings 2026-10-26 14:33 export, CWE-1333): the
        // domain bounds FIRST — trim once, reject past the login store's
        // 50-character address domain (V13), and only then evaluate the
        // EMAIL_SHAPE pattern. The pre-fix order ran the quadratic
        // backtracking matcher on the raw unbounded input (an
        // authenticated caller could burn shared CPU with an oversized
        // invalid destination before any limit applied).
        if (newEmail == null) {
            throw new IllegalArgumentException("A valid new email address is required");
        }
        String trimmedTarget = newEmail.trim();
        if (trimmedTarget.length() > 50 || !EMAIL_SHAPE.matcher(trimmedTarget).matches()) {
            throw new IllegalArgumentException("A valid new email address is required");
        }
        // The case-insensitive account-identifier discipline (the CodeRabbit
        // integrity round): the admin path's requireEmail lowercases before
        // storing, so the self-service path must too — a mixed-case target
        // would otherwise store as-is, compare equal case-insensitively here,
        // and then MISS the case-sensitive existsBySubject/userExists lookups
        // at confirm time (two accounts for one address, logins that never
        // resolve).
        String target = trimmedTarget.toLowerCase(Locale.ROOT);
        // The login store's own domain (the round-2 CodeRabbit finding): the
        // address becomes the login username — auth_users.username caps at
        // 50 characters (V13), and the failure would otherwise surface at
        // confirm time as a storage error AFTER the mail leg, instead of the
        // clean 400 at the request boundary.
        if (target.length() > 50) {
            throw new IllegalArgumentException(
                    "The new email address exceeds 50 characters — the login store's own limit");
        }
        if (target.equalsIgnoreCase(subject)) {
            throw new IllegalArgumentException("The new email address equals the current one");
        }
        // The 2026-11-08 #810 round-6 review (Minor · Security & Privacy):
        // the verify-then-issue window serializes with every secret
        // rotation. The rotation surfaces (PasswordResetService.
        // completeReset, AccountSelfServiceTokenService.changePassword)
        // kill the outstanding EMAIL_CHANGE rights when they rotate — but
        // a request that verified the OLD password could still ISSUE a
        // fresh right AFTER the rotation's invalidation ran (read-verify …
        // rotate-and-invalidate … issue: the minted right outlived the
        // secret regime that authorized it, and its confirm leg would move
        // the login to the requester's address). The SAME per-user
        // advisory auth-state lock the M2 family rides
        // (marketplace.user-auth:<id>, transaction-scoped) closes the
        // window from BOTH sides: either this verify-and-issue commits
        // first and the waiting rotation's invalidateOutstanding kills the
        // just-minted right, or the rotation commits first and the
        // verification below reads the NEW hash — the old password fails
        // with the classified denial.
        User user = userRepository.findBySubject(subject)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No account for the authenticated subject: " + subject));
        userRepository.lockUserAuth(user.getId().toString());
        UserDetails stored = requireLogin(subject);
        if (!encoder.getObject().matches(rawPassword, stored.getPassword())) {
            throw new BadCredentialsException("Password verification failed");
        }
        // B22 (IDP-04 — the 2026-11-03 adversarial review, review-pr-806):
        // the pending pair moves ONLY when a live link is actually issued.
        // issue()'s replacement rule already consumes the prior token when
        // it re-issues (the V112 live-token unique index), but the
        // throttled window — a re-request inside minIssueInterval —
        // answers the enumeration-safe silent success and sends NOTHING:
        // writing the pending target BEFORE that decision left the STILL
        // LIVE prior link bound to a target it was never issued for (two
        // sequential requests A then B, the second throttled: link A
        // confirmed and applied B). The pair coherence lives here now:
        // the pending target changes hand-in-hand with the live link, so
        // a redeemed right ALWAYS applies the pair it was issued for.
        authActionTokenService.issue(subject, AuthActionTokenPurpose.EMAIL_CHANGE, EMAIL_CHANGE_TTL)
                .ifPresent(issued -> {
                    user.requestEmailChange(target);
                    eventPublisher.publishEvent(new com.marketplace.shared.api
                            .EmailChangeRequestedEvent(user.getId(), subject, target,
                            issued.rawToken(), issued.expiresAt()));
                    // The 2026-11-05 #810 round review (Minor · Functional
                    // Correctness): the log records the RIGHT the system
                    // actually issued — the throttled window issues NOTHING
                    // (the enumeration-safe silent success), so a computed
                    // "now + TTL" line used to log an expiry that never
                    // existed.
                    log.info("Email change requested: subject={}, expiresAt={}", subject,
                            issued.expiresAt());
                });
    }

    /**
     * The confirm leg — consumes the right, moves both stores, answers the
     * new email.
     *
     * <p>The 2026-11-06 #806 round review (Major · Security &amp;
     * Privacy): the redemption is bound to the authenticated CALLER — the
     * pinned Saleor 3.23.45 contract ({@code AuthorizationFilters.
     * AUTHENTICATED_USER} on the mutation's {@code perform_mutation})
     * resolves the user from the request context and rejects any token
     * whose {@code user_id} claim belongs to someone else. The owner
     * verdict runs on the {@link AuthActionTokenService#accountOf}
     * pre-redemption read — BEFORE the consumption — so a mismatched
     * authenticated caller answers the classified denial while the
     * legitimate owner's one-time right SURVIVES the probe (never a
     * denial-of-service that burns the right); the consumption itself
     * stays one-time on the happy path.</p>
     *
     * @param caller the authenticated caller, who must own the right
     * @param token  the one-time email-change right
     * @return the confirmed new email address
     */
    @Observed(name = "account.email_change.confirm")
    public String confirmChange(Authentication caller, String token) {
        String callerSubject = caller.getName();
        // The owner verdict on the pre-redemption read (CWE-367's own
        // seam): an unknown token answers the SAME unknown-token denial
        // the consumption would (the accountOf javadoc's own contract);
        // a token that belongs to ANOTHER account answers the
        // caller-mismatch denial WITHOUT consuming it.
        String ownerSubject = authActionTokenService
                .accountOf(token, AuthActionTokenPurpose.EMAIL_CHANGE)
                .orElseThrow(() -> new BadRequestException(
                        "This email change link is invalid or has already been used"));
        if (!ownerSubject.equals(callerSubject)) {
            throw new ConflictException(
                    "The email change right belongs to a different account");
        }
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
