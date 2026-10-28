package com.marketplace.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Row 32 (agent findings 2026-10-25, CWE-367): the cross-instance
 * publication/withdrawal serialization, proven on a REAL PostgreSQL with
 * two gateway instances ("A" and "B") sharing the one database — the exact
 * deployment shape the finding describes (Spring Modulith's asynchronous
 * listeners each run in their own transaction, on any instance).
 *
 * <p><b>Why two gateway objects and not the context bean:</b> the race is
 * BETWEEN INSTANCES — separate stripe arrays, separate transactions, one
 * shared database. The vector stores are mocks with latches: the
 * serialization seam under proof is the per-source advisory transaction
 * lock in the shared database (observable in {@code pg_stat_activity} as a
 * backend waiting on {@code wait_event = 'advisory'}), not the vector
 * store's own storage.</p>
 *
 * <p><b>Determinism:</b> neither scenario relies on sleep-racing. The
 * holder's critical section is parked on a latch INSIDE its (mocked)
 * vector operation — after the advisory lock and the withdrawal record —
 * so the racer provably starts while the lock is held; the racer's wait is
 * itself confirmed through {@code pg_stat_activity} before the holder
 * commits. The interleaving the finding describes is constructed by hand,
 * and the assertion is whether the protocol turns it into the safe
 * outcome.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.ai.google.genai.embedding.api-key=test-key",
        "spring.ai.model.embedding.text=google-genai",
        "spring.ai.vectorstore.pgvector.initialize-schema=false"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AiKnowledgePublishWithdrawSerializationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * The finding's exact scenario: instance B's withdrawal completes
     * BETWEEN instance A's guard read and its vector insertion. With the
     * protocol, A's guard read happens UNDER the per-source advisory lock —
     * A waits for B's commit, then sees the durable record and skips: the
     * withdrawn content is never re-exposed.
     */
    @Test
    void aPublicationRacingAnotherInstancesWithdrawalWaitsForItsCommitThenSkips() throws Exception {
        String sourceId = UUID.randomUUID().toString();
        CountDownLatch withdrawalInsideVectorDelete = new CountDownLatch(1);
        CountDownLatch releaseWithdrawal = new CountDownLatch(1);
        AtomicBoolean publicationInserted = new AtomicBoolean(false);

        AiKnowledgeGateway instanceB = gateway(vectorStoreOfWithdrawal(
                withdrawalInsideVectorDelete, releaseWithdrawal));
        Thread withdrawal = startedThread(g -> g.markWithdrawn(sourceId), instanceB);
        // B now holds the per-source advisory lock with an uncommitted record,
        // parked inside its vector delete.
        assertThat(withdrawalInsideVectorDelete.await(20, java.util.concurrent.TimeUnit.SECONDS))
                .as("the withdrawal must reach its vector delete (lock held, record uncommitted)")
                .isTrue();

        VectorStore publicationStore = mock(VectorStore.class);
        doAnswer(invocation -> {
            publicationInserted.set(true);
            return null;
        }).when(publicationStore).add(any());
        AiKnowledgeGateway instanceA = gateway(publicationStore);
        Thread publication = startedThread(
                g -> g.replacePublicSource(new AiKnowledgeGateway.AiKnowledgeSource(
                        sourceId, "knowledge-entry", "Racing publication of soon-withdrawn content")),
                instanceA);

        // The publication is BLOCKED on the advisory lock — the race window
        // the finding describes is now open, deterministically.
        awaitAdvisoryLockWaiter();

        // B commits: record + vector delete + lock release, atomically.
        releaseWithdrawal.countDown();
        joinAll(withdrawal, publication);

        // A proceeded under the committed state: the guard saw the record
        // and the withdrawn content was never re-inserted.
        assertThat(publicationInserted.get())
                .as("the racing publication must skip — the withdrawal committed first")
                .isFalse();
        assertThat(durableWithdrawalCount(sourceId)).isEqualTo(1);
    }

    /**
     * The reversed order: instance A's publication completes first, and
     * instance B's racing withdrawal waits for its commit — then removes
     * the fresh vectors and records the withdrawal. Whichever side wins
     * the lock, the end state never exposes withdrawn content.
     */
    @Test
    void aWithdrawalRacingAnotherInstancesPublicationWaitsForItsCommitThenRemovesTheVectors() throws Exception {
        String sourceId = UUID.randomUUID().toString();
        CountDownLatch publicationInsideVectorAdd = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        AtomicBoolean withdrawalDeleted = new AtomicBoolean(false);

        VectorStore publicationStore = mock(VectorStore.class);
        doAnswer(invocation -> {
            withdrawalInsideVectorDeleteParked(publicationInsideVectorAdd, releasePublication);
            return null;
        }).when(publicationStore).add(any());
        AiKnowledgeGateway instanceA = gateway(publicationStore);
        Thread publication = startedThread(
                g -> g.replacePublicSource(new AiKnowledgeGateway.AiKnowledgeSource(
                        sourceId, "knowledge-entry", "Publication a racing withdrawal must wait for")),
                instanceA);
        // A now holds the per-source advisory lock, parked inside its vector
        // add (uncommitted).
        assertThat(publicationInsideVectorAdd.await(20, java.util.concurrent.TimeUnit.SECONDS))
                .as("the publication must reach its vector add (lock held, vectors uncommitted)")
                .isTrue();

        VectorStore withdrawalStore = mock(VectorStore.class);
        doAnswer(invocation -> {
            withdrawalDeleted.set(true);
            return null;
        }).when(withdrawalStore).delete(any(Filter.Expression.class));
        AiKnowledgeGateway instanceB = gateway(withdrawalStore);
        Thread withdrawal = startedThread(g -> g.markWithdrawn(sourceId), instanceB);

        // The withdrawal is BLOCKED on the advisory lock held by A's
        // still-open publication transaction.
        awaitAdvisoryLockWaiter();

        // A commits: vectors visible; the lock releases only with the commit.
        releasePublication.countDown();
        joinAll(publication, withdrawal);

        // B proceeded after the commit: the durable record exists and the
        // fresh vectors were removed by the withdrawal's delete.
        assertThat(withdrawalDeleted.get())
                .as("the racing withdrawal must remove the committed vectors")
                .isTrue();
        assertThat(durableWithdrawalCount(sourceId)).isEqualTo(1);
    }

    /*
     * The helpers below build the two "instances". Both use the application's
     * real transaction manager and the shared database — the protocol's
     * seams are exactly the production ones.
     */

    private AiKnowledgeGateway gateway(VectorStore vectorStore) {
        return new AiKnowledgeGateway(vectorStore, new AiWithdrawnSourceStore(jdbcTemplate), transactionManager);
    }

    private VectorStore vectorStoreOfWithdrawal(
            CountDownLatch withdrawalInsideVectorDelete, CountDownLatch releaseWithdrawal) {
        VectorStore withdrawalStore = mock(VectorStore.class);
        doAnswer(invocation -> {
            withdrawalInsideVectorDeleteParked(withdrawalInsideVectorDelete, releaseWithdrawal);
            return null;
        }).when(withdrawalStore).delete(any(Filter.Expression.class));
        return withdrawalStore;
    }

    private void withdrawalInsideVectorDeleteParked(CountDownLatch reached, CountDownLatch release) {
        reached.countDown();
        try {
            assertThat(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the parked vector operation must be released by the test")
                    .isTrue();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("parked vector operation interrupted", e);
        }
    }

    private final java.util.List<AtomicReference<Throwable>> startedThreadFailures =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private Thread startedThread(java.util.function.Consumer<AiKnowledgeGateway> operation, AiKnowledgeGateway gateway) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                operation.accept(gateway);
            }
            catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.setUncaughtExceptionHandler((t, e) -> failure.set(e));
        startedThreadFailures.add(failure);
        thread.start();
        return thread;
    }

    private void joinAll(Thread... threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(30_000);
            assertThat(thread.isAlive()).as("thread %s must finish", thread.getName()).isFalse();
        }
        for (AtomicReference<Throwable> failure : startedThreadFailures) {
            assertThat(failure.get()).as("no gateway operation may fail").isNull();
        }
    }

    /**
     * Blocks until some backend is waiting to acquire an advisory lock —
     * the observable proof that the racing operation reached the per-source
     * protocol's serialization point instead of slipping past it.
     */
    private void awaitAdvisoryLockWaiter() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Integer waiters = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity "
                            + "WHERE wait_event_type = 'Lock' AND wait_event = 'advisory'",
                    Integer.class);
            if (waiters != null && waiters > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no backend reached the advisory lock wait within 20s "
                + "— the racing operation did not serialize");
    }

    private Integer durableWithdrawalCount(String sourceId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_withdrawn_sources WHERE source_id = ?",
                Integer.class, sourceId);
    }
}
