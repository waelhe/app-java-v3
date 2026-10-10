package com.marketplace.shared.security;

import java.util.List;

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
     * a withdrawn assignment ({@code user_role_assignments}, V182) is
     * fail-OPEN inside every live session whose cached authorities still
     * hold the role (the V183 effective-authorities view repairs the
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

    private void expireAllSessions(String username, String reason) {
        List<SessionInformation> sessions = sessionRegistry.getAllSessions(username, false);
        for (SessionInformation session : sessions) {
            session.expireNow();
        }
        logger.info("Sessions expired: principal={}, count={}, reason={}",
                username, sessions.size(), reason);
    }
}
