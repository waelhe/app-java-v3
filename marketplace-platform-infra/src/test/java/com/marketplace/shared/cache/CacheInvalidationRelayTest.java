package com.marketplace.shared.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Unit tests for {@link CacheInvalidationRelay}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Each requested cache name is relayed to the Redis publisher</li>
 *   <li>The listener is bound to the AFTER_COMMIT transaction phase, so the
 *       Redis publish can never happen before the business transaction
 *       commits (the core of the transactional consistency fix)</li>
 *   <li>Fallback execution stays disabled: without a running transaction
 *       the event is discarded rather than published eagerly</li>
 * </ul>
 *
 * <p>Reference: Spring Framework — Transactional Events:
 * "The valid phases are BEFORE_COMMIT, AFTER_COMMIT (default), AFTER_ROLLBACK,
 * as well as AFTER_COMPLETION [...] If no transaction is running, the listener
 * is not invoked at all, since we cannot honor the required semantics."
 * https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html
 */
@ExtendWith(MockitoExtension.class)
class CacheInvalidationRelayTest {

    @Mock
    private CacheInvalidationPublisher publisher;

    @InjectMocks
    private CacheInvalidationRelay relay;

    @Test
    void relaysEachRequestedCacheNameToPublisher() {
        relay.onCacheInvalidationRequested(
                new CacheInvalidationRequested(List.of("catalog-active", "catalog-search")));

        verify(publisher).publishInvalidation("catalog-active");
        verify(publisher).publishInvalidation("catalog-search");
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void emptyRequestResultsInNoRedisInteraction() {
        relay.onCacheInvalidationRequested(new CacheInvalidationRequested(List.of()));

        verifyNoMoreInteractions(publisher);
    }

    @Test
    void listenerIsBoundToAfterCommitPhase() throws Exception {
        Method listener = CacheInvalidationRelay.class
                .getMethod("onCacheInvalidationRequested", CacheInvalidationRequested.class);
        TransactionalEventListener annotation =
                listener.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void listenerDoesNotFallBackOutsideTransactions() throws Exception {
        Method listener = CacheInvalidationRelay.class
                .getMethod("onCacheInvalidationRequested", CacheInvalidationRequested.class);
        TransactionalEventListener annotation =
                listener.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation.fallbackExecution()).isFalse();
    }
}
