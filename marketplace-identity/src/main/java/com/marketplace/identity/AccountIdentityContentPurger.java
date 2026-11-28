package com.marketplace.identity;

import com.marketplace.shared.api.AccountIdentityErased;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Completes the account-erasure flow after the identity transaction commits.
 *
 * <p>The identity command publishes {@link AccountIdentityErased} in the same
 * transaction that pseudonymizes the user and removes the login identity.
 * Spring Modulith persists a publication for this listener atomically with
 * that transaction. This consumer therefore cannot observe an uncommitted
 * account state, and a process failure does not silently lose the purge.</p>
 *
 * <p>The listener is the reference's durable, asynchronous form
 * ({@link ApplicationModuleListener} — {@code @Async} +
 * {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Transactional(REQUIRES_NEW)}, the composition verified against the
 * shipped spring-modulith-events-api source and documented in
 * {@code AccountStatusSessionInvalidator}'s own rationale): the purge runs in
 * its own thread after the identity commit, so the member's erasure response
 * is honest and never carries the purge's weight — nor a storage outage's
 * 5xx after the commit. The deliberate contrast with the session
 * invalidator's plain synchronous form is per-case: there the in-flight
 * window between the response and the markers landing was the defect; here
 * that same window is the requirement — the purge is a heavy multi-module
 * operation the response must not block on.</p>
 *
 * <p>{@link AuthoredContentPurgeService} fans out to module-owned adapters,
 * each of which commits independently. A storage outage in one adapter is
 * isolated there: the remaining adapters — and the b-4 audit stage below —
 * still run on this delivery, then the summary
 * {@link AuthoredContentPurgeIncompleteException} is rethrown (never
 * swallowed), so the event publication stays incomplete and the existing
 * {@code EventPublicationResubmission} sweep resubmits exactly the unfinished
 * legs. The administrator's {@code POST /users/{id}/purge-content} surface
 * remains available as an explicit idempotent repair/retry operation.</p>
 */
@Component
public class AccountIdentityContentPurger {

    private static final Logger log = LoggerFactory.getLogger(AccountIdentityContentPurger.class);
    private static final String REASON = "account identity erased";
    private static final String ACTOR = "system";

    private final AuthoredContentPurgeService authoredContentPurgeService;
    private final AuditHistoryPurgeService auditHistoryPurgeService;

    public AccountIdentityContentPurger(AuthoredContentPurgeService authoredContentPurgeService,
                                        AuditHistoryPurgeService auditHistoryPurgeService) {
        this.authoredContentPurgeService = authoredContentPurgeService;
        this.auditHistoryPurgeService = auditHistoryPurgeService;
    }

    /**
     * Completes b-3 authored-content purge and b-4 audit-identity purge only
     * after pseudonymization is committed. The content stage runs first so the
     * audit stage can recover the original subject closure from users_aud
     * before deleting the mirror. Both services reject a live account and
     * both stages are idempotent, so a resubmitted publication safely resumes.
     *
     * <p>Isolation: when some b-3 adapters fail, the rest — and b-4 — still
     * run on THIS delivery (both stages are idempotent, and b-4's closure
     * recovery reads users_aud rows nothing in this delivery consumed);
     * the summary exception is then rethrown so the registry keeps the
     * publication open for the resubmission sweep.</p>
     */
    @ApplicationModuleListener
    public void onAccountIdentityErased(AccountIdentityErased event) {
        int contentRowsPurged;
        try {
            contentRowsPurged = authoredContentPurgeService.purge(event.userId(), REASON, ACTOR);
        } catch (AuthoredContentPurgeIncompleteException incomplete) {
            // The failing adapters' own error lines are already logged by the
            // orchestration; this line names the summary for the operator.
            log.error("Account erasure content stage incomplete: userId={}, failedAdapters={} — "
                            + "b-4 still runs on this delivery, then the publication stays open "
                            + "for the resubmission sweep",
                    event.userId(), incomplete.failedAdapters());
            // b-4 is deliberately second even on the incomplete leg: its
            // closure is recovered from users_aud before deleting that
            // mirror, and its idempotent scrub is safe to repeat on the
            // resubmitted delivery that finishes the failing adapters.
            auditHistoryPurgeService.purge(event.userId(), REASON, ACTOR);
            // Never swallowed: the rethrow is what keeps the publication
            // incomplete (the registry's own completion marking).
            throw incomplete;
        }
        // b-4 is deliberately second: its closure is recovered from users_aud
        // before deleting that mirror, and its scrub must include every audit
        // column left by the b-3 authored-content adapters.
        var auditResult = auditHistoryPurgeService.purge(event.userId(), REASON, ACTOR);
        log.info("Account erasure purge completed: userId={}, contentRowsPurged={}, "
                        + "auditCellsScrubbed={}, userAuditRowsDeleted={}",
                event.userId(), contentRowsPurged, auditResult.scrubbedRows(),
                auditResult.usersAudRowsDeleted());
    }
}
