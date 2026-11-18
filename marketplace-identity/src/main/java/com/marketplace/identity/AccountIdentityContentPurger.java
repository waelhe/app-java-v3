package com.marketplace.identity;

import com.marketplace.shared.api.AccountIdentityErased;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Completes the account-erasure flow after the identity transaction commits.
 *
 * <p>The identity command publishes {@link AccountIdentityErased} in the same
 * transaction that pseudonymizes the user and removes the login identity.
 * Spring Modulith persists a publication for this listener atomically with
 * that transaction. This consumer therefore cannot observe an uncommitted
 * account state, and a process failure does not silently lose the purge.</p>
 *
 * <p>The listener deliberately suspends transaction resources after commit.
 * {@link AuthoredContentPurgeService} fans out to module-owned adapters, each
 * of which commits independently. A failed adapter leaves its own work
 * retryable; the failed event publication is recovered by the existing
 * {@code EventPublicationResubmission} sweep. The administrator's
 * {@code POST /users/{id}/purge-content} surface remains available as an
 * explicit idempotent repair/retry operation.</p>
 */
@Component
public class AccountIdentityContentPurger {

    private static final Logger log = LoggerFactory.getLogger(AccountIdentityContentPurger.class);
    private static final String REASON = "account identity erased";
    private static final String ACTOR = "system";

    private final AuthoredContentPurgeService authoredContentPurgeService;

    public AccountIdentityContentPurger(AuthoredContentPurgeService authoredContentPurgeService) {
        this.authoredContentPurgeService = authoredContentPurgeService;
    }

    /**
     * Purges the identity's authored content only after pseudonymization is
     * committed. The service itself rejects a live account and each module
     * adapter is idempotent, so a resubmitted publication safely resumes.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onAccountIdentityErased(AccountIdentityErased event) {
        int purgedRows = authoredContentPurgeService.purge(event.userId(), REASON, ACTOR);
        log.info("Account erasure content purge completed: userId={}, rowsPurged={}",
                event.userId(), purgedRows);
    }
}
