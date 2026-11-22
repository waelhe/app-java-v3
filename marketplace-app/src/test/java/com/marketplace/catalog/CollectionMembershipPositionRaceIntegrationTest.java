package com.marketplace.catalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B6 (CAT-01 — the 2026-11-03 adversarial review, review-pr-808): the
 * membership position allocation's own race law, measured on the REAL
 * chain (real PostgreSQL + real Redis + the real Flyway schema). The
 * review's measured defect: {@code appendMembership}'s read-then-mint
 * derivation (live MAX(position)+1) had NO serialization and NO index —
 * two concurrent {@code collectionAddProducts} calls both read the same
 * maximum and mint the SAME next position silently, and a concurrent
 * reorder interleaves the same way; the display order turns unstable
 * with no error anywhere on the wire.
 *
 * <p><b>The corrective shape under test (the media registry's own
 * A-17/C.7 discipline + the V237 assignment registry's position
 * index):</b> the advisory transaction lock
 * ({@code lockCollectionPositionAllocation}) serializes every position
 * writer per collection BEFORE the membership is read, the V301 partial
 * unique index ({@code uq_collection_products_position_live}) is the
 * backstop, and the reorder's two-phase rewrite (park beyond max, flush,
 * write dense) keeps every intermediate state unique under the index.
 * The races here run REAL threads against REAL transactions — the
 * rotation suite's own CyclicBarrier pattern — because the guarantee is
 * exactly a concurrency guarantee: a mocked repository can never fail
 * this test.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CollectionMembershipPositionRaceIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the house precedent.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @MockitoBean
    com.marketplace.shared.api.BookingParticipantProvider bookingParticipantProvider;

    @MockitoBean
    com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    @Autowired
    private CollectionWriteService collectionWriteService;

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private CollectionProductRepository collectionProductRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private com.marketplace.identity.UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The holder leg's raw JDBC source for the lock-acquisition pin. */
    @Autowired
    private javax.sql.DataSource dataSource;

    /** This test's own rows — FK-safe cleanup after every leg. */
    private final List<UUID> seededCollectionIds = new ArrayList<>();
    private final List<UUID> seededProductIds = new ArrayList<>();
    private UUID providerId;
    private String storeCategoryCode;

    @BeforeEach
    void seedReferenceRows() {
        asAdmin();
        providerId = UUID.randomUUID();
        userRepository.save(new com.marketplace.identity.User(providerId, providerId.toString(), null,
                "b6-race test provider", com.marketplace.identity.UserRole.PROVIDER));

        storeCategoryCode = "b6race-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("""
                INSERT INTO store_categories
                    (id, code, name_en, name_ar, position, is_deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, FALSE, 0, now(), now())
                """, UUID.randomUUID(), storeCategoryCode, "b6-race test", "اختبار سباق المراكز");
    }

    @AfterEach
    void removeSeededRows() {
        // Hard-delete THIS test's own artifacts (dependents before
        // principals — the CollectionGraphQlIntegrationTest cleanup law).
        for (UUID collectionId : new ArrayList<>(seededCollectionIds)) {
            jdbcTemplate.update("DELETE FROM collection_products_aud WHERE id IN "
                    + "(SELECT id FROM collection_products WHERE collection_id = ?)", collectionId);
            jdbcTemplate.update("DELETE FROM collection_products WHERE collection_id = ?", collectionId);
            jdbcTemplate.update("DELETE FROM collections_aud WHERE id = ?", collectionId);
            jdbcTemplate.update("DELETE FROM collections WHERE id = ?", collectionId);
        }
        jdbcTemplate.update("DELETE FROM collection_products_aud WHERE id IN "
                + "(SELECT id FROM collection_products WHERE product_id IN "
                + "(SELECT id FROM products WHERE store_category_code = ?))", storeCategoryCode);
        jdbcTemplate.update("DELETE FROM collection_products WHERE product_id IN "
                + "(SELECT id FROM products WHERE store_category_code = ?)", storeCategoryCode);
        jdbcTemplate.update("DELETE FROM products WHERE store_category_code = ?", storeCategoryCode);
        jdbcTemplate.update("DELETE FROM store_categories WHERE code = ?", storeCategoryCode);
        userRepository.deleteById(providerId);
        SecurityContextHolder.clearContext();
    }

    /**
     * The race the review measured: two callers appending DIFFERENT
     * products to the SAME collection at the same instant. Without the
     * advisory lock both read the same live MAX(position) and mint the
     * same next position; with it the appends serialize and the positions
     * come out distinct — and the partial unique index (the leg below)
     * keeps any slipping path honest.
     */
    @Test
    void twoConcurrentAppendsOnTheSameCollectionMintDistinctPositions() throws Exception {
        Collection collection = persistCollection();
        Product first = persistProduct("b6 race A");
        Product second = persistProduct("b6 race B");

        List<com.marketplace.catalog.spi.CollectionMutationResult> results = runConcurrently(
                () -> collectionWriteService.addProducts(collection.getId(), List.of(first.getId())),
                () -> collectionWriteService.addProducts(collection.getId(), List.of(second.getId())));

        // Both appends succeed (the pair index only guards the SAME
        // product twice — different products are different members).
        assertThat(results).hasSize(2);
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(1).success()).isTrue();

        List<CollectionProduct> membership = collectionProductRepository
                .findByCollectionIdOrderByPositionAscIdAsc(collection.getId());
        assertThat(membership).hasSize(2);
        // THE point: distinct live positions (the serialized allocation),
        // contiguous 1..2 (the append's own law).
        assertThat(membership.stream().mapToInt(CollectionProduct::getPosition))
                .containsExactlyInAnyOrder(1, 2);
    }

    /**
     * The interleaving arm: an append racing a reorder of the SAME
     * collection. The advisory lock serializes both writers; the
     * reorder's two-phase rewrite stays unique under the V301 index at
     * every intermediate state; the final membership is dense and
     * conflict-free.
     */
    @Test
    void aConcurrentAppendAndReorderKeepTheMembershipDenseAndUnique() throws Exception {
        Collection collection = persistCollection();
        Product first = persistProduct("b6 race 1");
        Product second = persistProduct("b6 race 2");
        Product third = persistProduct("b6 race 3");
        collectionWriteService.addProducts(collection.getId(),
                List.of(first.getId(), second.getId(), third.getId()));
        Product late = persistProduct("b6 race 4");

        runConcurrently(
                () -> collectionWriteService.addProducts(collection.getId(), List.of(late.getId())),
                () -> collectionWriteService.reorderProducts(collection.getId(),
                        List.of(new com.marketplace.catalog.spi.CollectionProductMove(third.getId(), -2))));

        List<CollectionProduct> membership = collectionProductRepository
                .findByCollectionIdOrderByPositionAscIdAsc(collection.getId());
        assertThat(membership).hasSize(4);
        // Dense, unique, 1..4 — regardless of which writer won the race.
        assertThat(membership.stream().mapToInt(CollectionProduct::getPosition))
                .containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    /**
     * The backstop itself: the V301 partial unique index
     * ({@code uq_collection_products_position_live}) rejects a duplicate
     * LIVE position outright at the database — the honest constraint
     * failure a slipping path would surface, never a silent duplicate.
     */
    @Test
    void theLivePositionUniquenessBackstopRejectsADuplicateInsert() {
        Collection collection = persistCollection();
        Product first = persistProduct("b6 backstop A");
        Product second = persistProduct("b6 backstop B");
        collectionWriteService.addProducts(collection.getId(), List.of(first.getId()));

        // A raw SQL insert minting the SAME live (collection, position)
        // pair the membership already carries — the index's own law.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO collection_products
                            (id, collection_id, product_id, position, is_deleted, version, created_at, updated_at)
                        VALUES (?, ?, ?, 1, FALSE, 0, now(), now())
                        """, UUID.randomUUID(), collection.getId(), second.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * The 2026-11-22 #807 review (Minor · Data Integrity): the older
     * CyclicBarrier legs start both writers at the CALL boundary — a
     * writer could run its whole service path before the peer ever
     * reaches the lock, so a slipped lock would still pass those tests.
     * This leg pins the race at the SERVICE's own acquisition point:
     * an open transaction takes the collection's advisory lock directly
     * (the same {@code hashtextextended} key the service's
     * {@code lockCollectionPositionAllocation} computes) and HOLDS it;
     * the service's append is then observed WAITING on the acquisition
     * (its future stays incomplete inside the hold window — proof it
     * reached the lock attempt, not just the method entry); the holder's
     * COMMIT releases it and the append completes with a dense position.
     */
    @Test
    void anAppendBlocksAtTheServicesOwnAdvisoryLockAcquisition() throws Exception {
        Collection collection = persistCollection();
        Product first = persistProduct("b6 lock-pin A");
        Product second = persistProduct("b6 lock-pin B");
        collectionWriteService.addProducts(collection.getId(), List.of(first.getId()));

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch lockHeld =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseHolder =
                new java.util.concurrent.CountDownLatch(1);
        // The holder rides a PLAIN JDBC connection (autocommit off) — the
        // CI-measured failure mode of the first cut was the JPA
        // TransactionTemplate path on a pool thread: the latch never fired
        // inside 30s and the holder's exception (if any) sat invisible in
        // its un-checked Future. A raw connection with an explicit commit
        // owns the transaction-scoped advisory lock with no Spring tx
        // machinery in the loop, and the holder's failure surface is
        // asserted, never swallowed.
        java.util.concurrent.Future<Void> holder = pool.submit(() -> {
            try (java.sql.Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    ps.setString(1, collection.getId().toString());
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                    }
                }
                lockHeld.countDown();
                releaseHolder.await(60, java.util.concurrent.TimeUnit.SECONDS);
                conn.commit(); // releases the transaction-scoped advisory lock
            } catch (InterruptedException parked) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        try {
            // The acquisition is live before the racing append starts — and
            // a holder-side failure must fail THIS test, not vanish.
            org.assertj.core.api.Assertions.assertThat(
                            lockHeld.await(60, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the holder transaction must own the advisory lock first (holder failure: "
                            + "%s)", holder.isDone() ? holder.toString() : "still running")
                    .isTrue();

            java.util.concurrent.Future<com.marketplace.catalog.spi.CollectionMutationResult> waiting =
                    pool.submit(() -> {
                        asAdmin();
                        return collectionWriteService.addProducts(collection.getId(), List.of(second.getId()));
                    });

            // The append reached the acquisition attempt and is WAITING on
            // it — the service path's own serialization point. A short
            // window (well under the hold's release) proves the wait is
            // the lock, not the method's other legs.
            org.assertj.core.api.Assertions.assertThat(waiting.isDone())
                    .as("the append must be parked at the advisory-lock acquisition while the holder owns it")
                    .isFalse();
            // Re-check after a beat so a fast-slip (lock released early,
            // append already done) cannot fake the parked state.
            java.lang.Thread.sleep(300);
            org.assertj.core.api.Assertions.assertThat(waiting.isDone())
                    .as("the append is still parked at the acquisition point inside the hold window")
                    .isFalse();

            // Release: the holder COMMITs, the advisory lock frees, and the
            // parked append completes with a dense live position.
            releaseHolder.countDown();
            com.marketplace.catalog.spi.CollectionMutationResult appended =
                    waiting.get(60, java.util.concurrent.TimeUnit.SECONDS);
            org.assertj.core.api.Assertions.assertThat(appended.success()).isTrue();
            // The holder leg itself must have completed cleanly.
            holder.get(10, java.util.concurrent.TimeUnit.SECONDS);

            List<CollectionProduct> membership = collectionProductRepository
                    .findByCollectionIdOrderByPositionAscIdAsc(collection.getId());
            org.assertj.core.api.Assertions.assertThat(membership).hasSize(2);
            org.assertj.core.api.Assertions.assertThat(membership.stream()
                            .mapToInt(CollectionProduct::getPosition))
                    .containsExactlyInAnyOrder(1, 2);
        } finally {
            releaseHolder.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * The 2026-11-06 session-9 #806 final review (Minor · Data Integrity):
     * V253's own law permits position 0 (the column's DEFAULT — a legacy
     * row can carry it), so a membership shaped {@code {0, 1}} parked its
     * two-phase rewrite at {@code max+1 = 2} — INSIDE the final 1..n
     * range — and phase 2's UPDATE order decided between success and a
     * transient 23505. The park base now clears BOTH the current and the
     * final ranges; the zero-starting swap rewrites densely regardless of
     * the flush order.
     */
    @Test
    void aZeroStartingMembershipReordersDenselyThroughTheParkWindow() {
        Collection collection = persistCollection();
        Product first = persistProduct("b6 zero A");
        Product second = persistProduct("b6 zero B");
        collectionWriteService.addProducts(collection.getId(),
                List.of(first.getId(), second.getId()));
        // The legacy zero: the column's own DEFAULT, the {0, 1} shape the
        // review measured (a raw SQL move mirrors the pre-law row's birth).
        jdbcTemplate.update("UPDATE collection_products SET position = 0 "
                + "WHERE collection_id = ? AND product_id = ?", collection.getId(), first.getId());

        com.marketplace.catalog.spi.CollectionMutationResult result =
                collectionWriteService.reorderProducts(collection.getId(),
                        List.of(new com.marketplace.catalog.spi.CollectionProductMove(
                                second.getId(), -1)));

        assertThat(result.success())
                .as("the zero-starting swap must succeed, never a transient 23505")
                .isTrue();
        List<CollectionProduct> membership = collectionProductRepository
                .findByCollectionIdOrderByPositionAscIdAsc(collection.getId());
        assertThat(membership).hasSize(2);
        // Dense 1..2 — the park window cleared both ranges.
        assertThat(membership.stream().mapToInt(CollectionProduct::getPosition))
                .containsExactly(1, 2);
        // And the swap actually happened: second now precedes first.
        assertThat(membership.get(0).getProductId()).isEqualTo(second.getId());
        assertThat(membership.get(1).getProductId()).isEqualTo(first.getId());
    }

    // ------------------------------------------------------------------
    // the harness
    // ------------------------------------------------------------------

    /** Runs both writers behind one CyclicBarrier and collects their results. */
    private <T> List<T> runConcurrently(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        try {
            Future<T> one = pool.submit(gated(startTogether, first));
            Future<T> two = pool.submit(gated(startTogether, second));
            T firstResult = one.get(60, TimeUnit.SECONDS);
            T secondResult = two.get(60, TimeUnit.SECONDS);
            List<T> results = new ArrayList<>();
            results.add(firstResult);
            results.add(secondResult);
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Each pool thread carries its own ADMIN security context (method gates read the calling thread). */
    private <T> Callable<T> gated(CyclicBarrier barrier, Callable<T> call) {
        return () -> {
            asAdmin();
            barrier.await(30, TimeUnit.SECONDS);
            return call.call();
        };
    }

    private void asAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("b6-race-admin", "n/a", "ROLE_ADMIN"));
    }

    private Collection persistCollection() {
        Collection collection = collectionRepository.save(Collection.create(
                "b6race-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                "B6 race collection", "مجموعة السباق", true));
        seededCollectionIds.add(collection.getId());
        return collection;
    }

    private Product persistProduct(String title) {
        Product product = productRepository.save(Product.register(
                storeCategoryCode, title, "b6-race fixture", 1000L, "SAR", providerId));
        seededProductIds.add(product.getId());
        return product;
    }
}
