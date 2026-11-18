package com.marketplace.identity;

import com.marketplace.shared.api.AccountIdentityErased;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pins the durable, post-commit hand-off from account identity erasure to the module purge orchestrator. */
class AccountIdentityContentPurgerTest {

    @Test
    void forwardsTheErasedAccountToTheExistingIdempotentPurgeOrchestrator() {
        AuthoredContentPurgeService purgeService = mock(AuthoredContentPurgeService.class);
        AccountIdentityContentPurger listener = new AccountIdentityContentPurger(purgeService);
        UUID userId = UUID.randomUUID();
        when(purgeService.purge(userId, "account identity erased", "system")).thenReturn(12);

        listener.onAccountIdentityErased(new AccountIdentityErased(userId, "member@example.test"));

        verify(purgeService).purge(userId, "account identity erased", "system");
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
