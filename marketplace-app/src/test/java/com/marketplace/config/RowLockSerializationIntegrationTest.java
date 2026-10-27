package com.marketplace.config;

import com.marketplace.community.ContentReport;
import com.marketplace.community.ContentReportRepository;
import com.marketplace.community.ContentReportService;
import com.marketplace.community.ModerationRule;
import com.marketplace.community.ModerationRuleRepository;
import com.marketplace.community.ReportReason;
import com.marketplace.community.ReportTargetType;
import com.marketplace.lending.Loan;
import com.marketplace.lending.LoanRepository;
import com.marketplace.lending.LoanStatus;
import com.marketplace.lending.LendingService;
import com.marketplace.orders.Order;
import com.marketplace.orders.OrderRepository;
import com.marketplace.orders.OrderStatus;
import com.marketplace.orders.OrdersService;
import com.marketplace.reviews.Review;
import com.marketplace.reviews.ReviewModerationStatus;
import com.marketplace.reviews.ReviewRepository;
import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part-2 review round 2 (the full review's CHANGES_REQUESTED on
 * {@code b841c2f50f}) — the three pessimistic serialization seams, each
 * pinned against the REAL Flyway schema with the row's write lock held
 * by a first transaction:
 *
 * <ol>
 *   <li><b>The dispute cycle (Minor, Stability):</b> the freeze's
 *       {@code ACTIVE} filter and the release's {@code DISPUTED} filter
 *       both read the loan through {@code findByIdForUpdate} — the second
 *       dispute event BLOCKS on the row's lock, then reads the committed
 *       truth and takes the honest no-op (never a stale-snapshot
 *       double write).</li>
 *   <li><b>The leg-creation read (Major, Stability):</b>
 *       {@code requireOrderFulfillmentForUpdate} serializes the split
 *       command and the confirmation listener with the direct-fulfillment
 *       gate on the order row itself — the finding's exact interleaving
 *       (a live leg created between the gate's active-legs count and its
 *       commit) is structurally impossible now: the serialized reader
 *       sees the committed FULFILLED truth and answers the honest
 *       409.</li>
 *   <li><b>The moderation hide (Major, Data Integrity):</b>
 *       {@code hidePublishedReview} reads the review under the row's
 *       write lock BEFORE the {@code PUBLISHED}-only filter — the second
 *       concurrent report transaction waits, reads the committed HIDDEN
 *       truth, takes the documented skip and returns empty: its own
 *       report creation COMMITS instead of dying on the inherited
 *       {@code @Version} optimistic conflict.</li>
 * </ol>
 *
 * <p>The blocking is PostgreSQL's own {@code FOR UPDATE} semantics — the
 * platform's serialization guarantee, made deterministic by the lock
 * itself (no probabilistic thread race: while the first transaction owns
 * the row's write lock, the second path CANNOT complete).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RowLockSerializationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches OrderReturnOverlapAggregateIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txm;

    @Autowired
    private LoanRepository loans;

    @Autowired
    private LendingService lending;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private OrdersService ordersService;

    @Autowired
    private ReviewRepository reviews;

    @Autowired
    private ContentReportService reports;

    @Autowired
    private ContentReportRepository contentReports;

    @Autowired
    private ModerationRuleRepository moderationRules;

    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void theDisputeFreezeSerializesOnTheLoanRow() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID borrower = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        seedUser(owner, "PROVIDER");
        seedUser(borrower, "CONSUMER");
        seedCategoryAndProduct(product, owner);
        UUID loanId = plantActiveLoan(owner, borrower, product);

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                new TransactionTemplate(txm).execute(status -> {
                    Loan loan = loans.findByIdForUpdate(loanId).orElseThrow();
                    lockHeld.countDown();
                    awaitQuietly(release);
                    // the first dispute event's freeze — the exact write the
                    // second event raced before the lock
                    loan.markDisputed();
                    loans.save(loan);
                    return null;
                });
            } catch (Throwable t) {
                holderFailure.set(t);
            }
        });
        holder.start();
        assertThat(lockHeld.await(10, TimeUnit.SECONDS))
                .as("the first transaction owns the loan row's write lock")
                .isTrue();

        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch readerDone = new CountDownLatch(1);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            readerStarted.countDown();
            try {
                // the second (redelivered) dispute event's freeze path
                lending.markDisputedFromDispute(UUID.randomUUID(), loanId);
            } catch (Throwable t) {
                readerFailure.set(t);
            } finally {
                readerDone.countDown();
            }
        });
        reader.start();
        assertThat(readerStarted.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(readerDone.await(500, TimeUnit.MILLISECONDS))
                .as("while the row's write lock is held, the second dispute path cannot complete")
                .isFalse();

        release.countDown();
        holder.join(10_000);
        assertThat(holderFailure.get()).isNull();
        assertThat(readerDone.await(10, TimeUnit.SECONDS)).isTrue();

        // the serialized reader read the committed DISPUTED truth — the
        // ACTIVE filter's honest no-op, never a stale double freeze
        assertThat(readerFailure.get()).isNull();
        assertThat(loans.findById(loanId).orElseThrow().getStatus()).isEqualTo(LoanStatus.DISPUTED);
    }

    @Test
    void theLegCreationReadSerializesWithTheFulfillmentGate() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        seedUser(buyer, "CONSUMER");
        seedUser(seller, "PROVIDER");
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id, consumer_id, seller_id, status, placed_at, total_amount_minor, currency)
                VALUES (?, ?, ?, 'CONFIRMED', ?, 50_000, 'SAR')
                """, orderId, buyer, seller, java.sql.Timestamp.from(T0));

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                new TransactionTemplate(txm).execute(status -> {
                    Order order = orders.findByIdForUpdate(orderId).orElseThrow();
                    lockHeld.countDown();
                    awaitQuietly(release);
                    // the direct fulfillment's own commit — the write the
                    // unlocked split path raced between the gate's count
                    // and its commit
                    order.fulfill(Instant.now());
                    orders.save(order);
                    return null;
                });
            } catch (Throwable t) {
                holderFailure.set(t);
            }
        });
        holder.start();
        assertThat(lockHeld.await(10, TimeUnit.SECONDS))
                .as("the fulfillment transaction owns the order row's write lock")
                .isTrue();

        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch readerDone = new CountDownLatch(1);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            readerStarted.countDown();
            try {
                // the split command's order read — the same seam the
                // confirmation listener rides
                ordersService.requireOrderFulfillmentForUpdate(orderId, buyer);
            } catch (Throwable t) {
                readerFailure.set(t);
            } finally {
                readerDone.countDown();
            }
        });
        reader.start();
        assertThat(readerStarted.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(readerDone.await(500, TimeUnit.MILLISECONDS))
                .as("while the fulfillment gate owns the order row, the leg-creation read cannot complete")
                .isFalse();

        release.countDown();
        holder.join(10_000);
        assertThat(holderFailure.get()).isNull();
        assertThat(readerDone.await(10, TimeUnit.SECONDS)).isTrue();

        // the serialized read sees the committed FULFILLED truth — the
        // honest 409, never a leg created for a fulfilled order
        assertThat(readerFailure.get()).isInstanceOf(ConflictException.class);
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.FULFILLED);
    }

    @Test
    void theEngineHideSerializesOnTheReviewRowAndTheSecondReportSurvives() throws Exception {
        // Part-2 review round 2's own follow-up (the incremental round's
        // measured nitpick): the REAL end-to-end shape — two concurrent
        // ContentReportService.createReport transactions crossing the rule
        // threshold together, and the SECOND REPORT'S OWN ROW is the
        // assertion: it survives (commits) instead of being taken down by
        // the optimistic-version failure the un-locked hide used to cause.
        UUID reviewer = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        seedUser(reviewer, "CONSUMER");
        seedUser(provider, "PROVIDER");
        UUID reviewId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, origin, moderation_status)
                VALUES (?, NULL, ?, ?, 5, 'ORGANIC', 'PUBLISHED')
                """, reviewId, reviewer, provider);

        // the rule at the threshold the finding describes: ONE existing
        // open report + TWO concurrent creators = both cross the bar
        UUID reporterA = UUID.randomUUID();
        UUID reporterB = UUID.randomUUID();
        UUID reporterC = UUID.randomUUID();
        seedUser(reporterA, "CONSUMER");
        seedUser(reporterB, "CONSUMER");
        seedUser(reporterC, "CONSUMER");
        moderationRules.save(ModerationRule.register(
                ReportTargetType.REVIEW, ReportReason.SPAM, 2));
        contentReports.save(ContentReport.report(
                reporterA, ReportTargetType.REVIEW, reviewId, ReportReason.SPAM));

        // the two concurrent threshold-crossing report transactions — the
        // engine rides each creation command's own unit of work, and the
        // hides serialize on the review row's write lock
        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> bFailure = new AtomicReference<>();
        AtomicReference<Throwable> cFailure = new AtomicReference<>();
        Thread reporterBThread = new Thread(() -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                reports.createReport(reporterB, ReportTargetType.REVIEW, reviewId, ReportReason.SPAM);
            } catch (Throwable t) {
                bFailure.set(t);
            } finally {
                done.countDown();
            }
        });
        Thread reporterCThread = new Thread(() -> {
            try {
                start.await(10, TimeUnit.SECONDS);
                reports.createReport(reporterC, ReportTargetType.REVIEW, reviewId, ReportReason.SPAM);
            } catch (Throwable t) {
                cFailure.set(t);
            } finally {
                done.countDown();
            }
        });
        reporterBThread.start();
        reporterCThread.start();

        assertThat(done.await(30, TimeUnit.SECONDS))
                .as("both report transactions complete — no hang, no rollback")
                .isTrue();

        // THE finding's assertion — the second report's own row survived:
        // neither transaction was taken down by the hide's serialization
        assertThat(bFailure.get()).isNull();
        assertThat(cFailure.get()).isNull();
        Integer landed = jdbc.queryForObject(
                "SELECT count(*) FROM content_reports WHERE target_id = ?", Integer.class, reviewId);
        assertThat(landed)
                .as("A's seed report + BOTH concurrent reports landed — the second report survives")
                .isEqualTo(3);
        assertThat(reviews.findById(reviewId).orElseThrow().getModerationStatus())
                .isEqualTo(ReviewModerationStatus.HIDDEN_BY_MODERATOR);
    }

    // ------------------------------------------------------------------
    // Seeding — the same shapes the neighboring integration tests use
    // ------------------------------------------------------------------

    private void seedUser(UUID id, String role) {
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, id, "lock-" + id, "lock-" + id + "@example.com",
                "row lock serialization test", role);
    }

    private void seedCategoryAndProduct(UUID productId, UUID ownerId) {
        String code = "lock-cat-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO store_categories (id, code, name_en, name_ar)
                VALUES (?, ?, 'Lock serialization', 'قفل التسلسل')
                """, UUID.randomUUID(), code);
        jdbc.update("""
                INSERT INTO products (id, store_category_code, title, description, price_minor, currency, provider_id)
                VALUES (?, ?, 'Lock serialization item', 'the row-lock seams ride', 100000, 'SAR', ?)
                """, productId, code, ownerId);
    }

    /** A loan planted through the real factory, then moved to the ACTIVE edge the dispute paths filter on. */
    private UUID plantActiveLoan(UUID ownerId, UUID borrowerId, UUID productId) {
        Loan loan = loans.save(Loan.request(productId, ownerId, borrowerId, T0, T1, 500L, "SAR", 250L));
        jdbc.update("UPDATE loans SET status = 'ACTIVE' WHERE id = ?", loan.getId());
        return loan.getId();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("lock-holder was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("lock-holder interrupted", e);
        }
    }
}
