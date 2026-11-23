package com.marketplace.shared.security;

import java.util.List;

import com.marketplace.shared.api.AccountCredentialsRotated;
import com.marketplace.shared.api.AccountIdentityErased;
import com.marketplace.shared.api.AccountLoginIdentityMoved;
import com.marketplace.shared.api.AccountSessionsRevoked;
import com.marketplace.shared.api.AccountStatusChanged;
import com.marketplace.shared.api.UserRoleAssignmentRevoked;
import com.marketplace.shared.api.UserRoleChanged;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * R8 (comprehensive-review-ar fix plan §4, Wave 1): expires the Spring
 * Session-backed sessions of an account the moment its login-side status or
 * role changes on the database, closing the "surviving session" gap of the
 * comprehensive review: {@code UserService.updateUserStatus} already removed
 * the account's {@code oauth2_authorization} rows (the refresh-resurrection
 * kill, L23), but a live form-login session kept authenticating and could
 * mint fresh authorization codes for the disabled account.
 *
 * <p><b>The official path, end to end.</b> The registry is the one
 * {@code SecurityConfig} already wires —
 * {@link SpringSessionBackedSessionRegistry} over the indexed
 * {@code FindByIndexNameSessionRepository} (Redis, {@code repository-type:
 * indexed}): its javadoc is the design contract ("A SessionRegistry that
 * retrieves session information from Spring Session, rather than maintaining
 * it itself"), Spring Session's {@code PrincipalNameIndexResolver} indexes
 * every saved session by {@code authentication.getName()} (the email
 * principal the form-login chain authenticates), and
 * {@link SessionInformation#expireNow()} persists the official expiry marker
 * ({@code EXPIRED_ATTR}) back into the session store. Enforcement is
 * framework-native: {@code ConcurrentSessionFilter} — registered by
 * {@code sessionManagement().maximumSessions()} in BOTH filter chains that
 * authenticate sessions (the form-login default chain and the authorization
 * server chain, see {@code SecurityConfig}) — reads
 * {@code getSessionInformation(sessionId)}, observes the expired marker,
 * invokes the logout handlers (the session row is invalidated in Redis) and
 * stops the chain, so the authorization endpoint never sees the request and
 * no code can be minted. The two-argument
 * {@code getAllSessions(principal, false)} is the only form the
 * {@code SessionRegistry} interface declares (spring-security-core 7.1.1) —
 * {@code false} skips already-expired sessions, which keeps a framework
 * resubmission idempotent.
 *
 * <p><b>Durability is the framework's, and so is the timing (CodeRabbit
 * round on the invalidator, adopted from the root).</b> The listeners carry
 * plain {@code @TransactionalEventListener(phase = AFTER_COMMIT)} plus
 * {@code @Transactional(REQUIRES_NEW)} — <em>not</em> Modulith's
 * {@code @ApplicationModuleListener} — because that composed annotation is
 * meta-annotated {@code @Async} (verified against the shipped
 * spring-modulith-events-api 2.1.1 source: {@code @Async} +
 * {@code @Transactional(REQUIRES_NEW)} + {@code @TransactionalEventListener}),
 * and async processing is <em>on</em> in this application by the framework's
 * own decision: {@code EventPublicationAutoConfiguration} (spring-modulith-
 * events-core 2.1.1, active here via {@code spring-modulith-events-jpa})
 * imports {@code AsyncEnablingConfiguration}, an {@code @EnableAsync}
 * configuration that only backs off when the application declares its own
 * async infrastructure — this application declares none. An async listener
 * would hand the expiry to the task executor and let the admin's 200 leave
 * before the markers land: the in-flight window the review round flagged. The
 * plain AFTER_COMMIT form runs in the <em>committing</em> thread — the
 * disable response and the session expiry are the same request — while the
 * publication row still commits atomically with the status/role flip and a
 * failed or crashed invalidation is still resubmitted by
 * {@code EventPublicationResubmission}: the registry's completion advisor
 * advises every {@code @TransactionalEventListener(AFTER_COMMIT)} method,
 * not only {@code @ApplicationModuleListener} ones (verified against the
 * shipped advisor's pointcut). No {@code catch} block by design — the house
 * convention (five removals predate this class). <b>The failure contract is
 * the framework's, stated precisely (review round 2, verified against the
 * shipped spring-tx 7.0.9 source):</b> an AFTER_COMMIT-phase listener is
 * invoked from the synchronization's {@code afterCompletion(int)} callback —
 * never from {@code afterCommit()} — and
 * {@code TransactionSynchronizationUtils.invokeAfterCompletion} catches
 * {@code Throwable} and logs it ("afterCompletion threw exception"), so a
 * failed expiry does <em>not</em> fail the admin PUT: the 200 reflects the
 * committed status flip, the failure lands in the error log, the publication
 * row stays incomplete for the resubmission sweep, and the residual window
 * self-closes because the session store <em>is</em> the Redis that would be
 * failing — a session that cannot be read cannot mint a code. The PUT
 * remains idempotently re-issuable
 * ({@code getAllSessions(principal, false)} skips already-expired sessions).
 *
 * <p><b>Asymmetry by direction.</b> A disable (or any role change) expires
 * every live session of the account; an enable expires nothing — zero
 * security value in dropping an enabled account's sessions, and the negative
 * test pins it. The role-change leg rides {@link UserRoleChanged} (which now
 * carries the username): the authorities cached inside every live session
 * are stale the moment the projection is replaced, so the sessions are
 * expired and the next authorization request re-authenticates against the
 * new {@code roles} claim source.
 */
@Component
public class AccountStatusSessionInvalidator {

    private static final Logger logger = LoggerFactory.getLogger(AccountStatusSessionInvalidator.class);

    private final SpringSessionBackedSessionRegistry<? extends Session> sessionRegistry;

    public AccountStatusSessionInvalidator(
            SpringSessionBackedSessionRegistry<? extends Session> sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Disabling an account expires all of its live sessions. Enabling keeps
     * them — the activation leg of the event is the complete domain fact for
     * future consumers, not an invalidation trigger (CodeRabbit round 1 on
     * the fix plan, adopted from the root, commit {@code e27a94b}).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAccountStatusChanged(AccountStatusChanged event) {
        if (event.enabled()) {
            logger.debug("Account enabled — sessions kept intact: {}", event.username());
            return;
        }
        expireAllSessions(event.username(), "account disabled");
    }

    /**
     * A role change expires all of the account's live sessions: the
     * authorities inside each session's security context are stale against
     * the replaced {@code auth_authorities} projection.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserRoleChanged(UserRoleChanged event) {
        expireAllSessions(event.username(),
                "role changed: " + event.previousRole() + " -> " + event.newRole());
    }

    /**
     * Phase 1 (the unified plan §10, D-03) — the multi-role REVOKE leg:
     * a withdrawn assignment ({@code user_role_assignments}, V187) is
     * fail-OPEN inside every live session whose cached authorities still
     * hold the role (the V188 effective-authorities view repairs the
     * source only at the NEXT login/token mint), so the sessions are
     * expired now. The GRANT leg publishes nothing by design — it is
     * fail-CLOSED for live sessions (the new authority is simply absent
     * until the next login), the documented asymmetry this consumer's
     * {@code AccountStatusChanged} leg already rides.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserRoleAssignmentRevoked(UserRoleAssignmentRevoked event) {
        expireAllSessions(event.username(), "role assignment revoked: " + event.role());
    }

    /**
     * Account erasure (CodeRabbit agent findings, row 7 / CWE-613): the
     * pseudonymization command (which the self-service deletion also
     * delegates to) deleted the login row, rewrote the subject and revoked
     * the issued authorizations — but the sessions minted before the
     * erasure hold their authenticated principal and its cached authorities
     * inside the session store. A surviving session of an erased
     * administrator keeps passing method authorization under a deleted
     * identity, so every session indexed under the ORIGINAL subject expires
     * with the commit (the same AFTER_COMMIT + REQUIRES_NEW durability as
     * every leg above — the failure contract is the framework's).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAccountIdentityErased(AccountIdentityErased event) {
        expireAllSessions(event.username(), "account identity erased (pseudonymization/self-deletion)");
    }

    /**
     * Credential rotation (CodeRabbit agent findings, row 14 / CWE-613):
     * password recovery replaces the stored verifier and revokes the issued
     * grants, but a live browser session authenticated under the OLD
     * password keeps its cached principal and can mint fresh authorization
     * codes — re-creating through the surviving session exactly the grants
     * the recovery just revoked. Every session of the account expires with
     * the commit; the owner re-authenticates with the new credential.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAccountCredentialsRotated(AccountCredentialsRotated event) {
        expireAllSessions(event.username(), "credentials rotated (password reset)");
    }

    /**
     * CodeRabbit agent findings (2026-10-26 14:33 export, row 7, CWE-613):
     * the login-identity move's own AFTER_COMMIT edge. The move revokes the
     * RETIRED subject's issued grants, but a live browser session
     * authenticated under that retired subject keeps its cached principal
     * and authorities — without this expiry it would keep minting fresh
     * authorization codes under the dead name, and a later demotion or
     * disablement (indexed under the REPLACEMENT subject) would never reach
     * it. Every session the retired identity authenticated expires with the
     * move's commit; the owner re-authenticates under the new address.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAccountLoginIdentityMoved(AccountLoginIdentityMoved event) {
        expireAllSessions(event.username(), "login identity moved");
    }

    /**
     * CodeRabbit agent findings (2026-10-26 14:33 export, row 13, CWE-613):
     * the owner's "sign out everywhere" — the stored OAuth authorizations
     * die inside the revocation's transaction, and this AFTER_COMMIT edge
     * completes the intent on the session side: a live browser session
     * otherwise keeps its authenticated security context and authorizes
     * again immediately, re-creating exactly the replacement grants the
     * revocation just removed, without any credential challenge.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAccountSessionsRevoked(AccountSessionsRevoked event) {
        expireAllSessions(event.username(), "all sessions revoked by the owner");
    }

    private void expireAllSessions(String username, String reason) {
        List<SessionInformation> sessions = sessionRegistry.getAllSessions(username, false);
        for (SessionInformation session : sessions) {
            session.expireNow();
        }
        logger.info("Sessions expired: principal={}, count={}, reason={}",
                username, sessions.size(), reason);
    }
}
