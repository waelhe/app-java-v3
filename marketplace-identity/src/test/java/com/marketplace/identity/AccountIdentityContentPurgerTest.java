package com.marketplace.identity;

import com.marketplace.identity.spi.AuditHistoryPurgeResult;
import com.marketplace.shared.api.AccountIdentityErased;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.ApplicationModuleListener;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the durable, asynchronous hand-off from account identity erasure to
 * the module purge orchestrator — and the isolation contract the async form
 * requires: a failing adapter set never aborts the later adapters (handled
 * inside the orchestrator) nor b-4, and is rethrown so the publication stays
 * open for the {@code EventPublicationResubmission} sweep.
 */
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
    void anIncompleteContentPurgeStillRunsB4ThenRethrowsForResubmission() {
        AuthoredContentPurgeService contentPurge = mock(AuthoredContentPurgeService.class);
        AuditHistoryPurgeService auditPurge = mock(AuditHistoryPurgeService.class);
        AccountIdentityContentPurger listener = new AccountIdentityContentPurger(contentPurge, auditPurge);
        UUID userId = UUID.randomUUID();
        when(contentPurge.purge(userId, "account identity erased", "system"))
                .thenThrow(new AuthoredContentPurgeIncompleteException(
                        userId, List.of("MediaPurgeAdapter")));
        when(auditPurge.purge(userId, "account identity erased", "system"))
                .thenReturn(new AuditHistoryPurgeResult(3, 1));

        // Never swallowed: the rethrow is what keeps the durable
        // publication incomplete for the resubmission sweep.
        assertThatThrownBy(() ->
                listener.onAccountIdentityErased(new AccountIdentityErased(userId, "member@example.test")))
                .isInstanceOf(AuthoredContentPurgeIncompleteException.class);

        // Isolation: b-4 still ran on the same delivery, in order.
        var order = inOrder(contentPurge, auditPurge);
        order.verify(contentPurge).purge(userId, "account identity erased", "system");
        order.verify(auditPurge).purge(userId, "account identity erased", "system");
    }

    @Test
    void listenerIsTheDurableAsynchronousApplicationModuleListener() throws Exception {
        Method method = AccountIdentityContentPurger.class.getMethod(
                "onAccountIdentityErased", AccountIdentityErased.class);

        // The reference's durable form: @Async + @TransactionalEventListener
        // (AFTER_COMMIT) + @Transactional(REQUIRES_NEW) — the composition the
        // AccountStatusSessionInvalidator rationale documents against the
        // shipped spring-modulith-events-api source. The registry persists
        // the publication atomically with the erasing transaction; a JVM
        // death between commit and completion is recovered by the sweep.
        assertThat(method.getAnnotation(ApplicationModuleListener.class))
                .as("the erasure purge runs as a durable, asynchronous publication")
                .isNotNull();
    }
}
