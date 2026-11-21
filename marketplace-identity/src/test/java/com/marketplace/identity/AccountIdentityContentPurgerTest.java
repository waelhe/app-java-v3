package com.marketplace.identity;

import com.marketplace.identity.spi.AuditHistoryPurgeResult;
import com.marketplace.shared.api.AccountIdentityErased;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.when;

/** Pins the durable, post-commit hand-off from account identity erasure to the module purge orchestrator. */
class AccountIdentityContentPurgerTest {

    @Test
    void forwardsTheErasedAccountToTheExistingIdempotentPurgeOrchestrator() {
        AuthoredContentPurgeService contentPurge = mock(AuthoredContentPurgeService.class);
        AuditHistoryPurgeService auditPurge = mock(AuditHistoryPurgeService.class);
        AccountIdentityContentPurger listener = new AccountIdentityContentPurger(contentPurge, auditPurge);
        UUID userId = UUID.randomUUID();
        when(contentPurge.purge(userId, "account identity erased", "system")).thenReturn(12);
        when(auditPurge.purge(userId, "account identity erased", "system"))
                .thenReturn(new AuditHistoryPurgeResult(7, 2));

        listener.onAccountIdentityErased(new AccountIdentityErased(userId, "member@example.test"));

        // The plan's b-4 closure is recovered from users_aud; b-3 therefore
        // runs first and b-4 remains the final audit scrub/delete stage.
        var order = inOrder(contentPurge, auditPurge);
        order.verify(contentPurge).purge(userId, "account identity erased", "system");
        order.verify(auditPurge).purge(userId, "account identity erased", "system");
    }

    @Test
    void listenerRunsAfterCommitAndSuspendsTheCompletedTransaction() throws Exception {
        Method method = AccountIdentityContentPurger.class.getMethod(
                "onAccountIdentityErased", AccountIdentityErased.class);

        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(method.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.NOT_SUPPORTED);
    }
}
