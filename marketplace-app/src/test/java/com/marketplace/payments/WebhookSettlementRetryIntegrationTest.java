package com.marketplace.payments;

import test.config.IntegrationContainers;
import test.config.ModuleTestConfig;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CodeRabbit #431 (adopted from the root 2026-09-28) — the retry half of the
 * settlement-transaction fix: a first confirm attempt that fails with a
 * retryable exception must not poison the webhook dispatch's carrier
 * transaction. Under the pre-fix REQUIRED propagation the first attempt's
 * exception marked the shared carrier rollback-only; the {@code @Retry}
 * loop's second attempt then "succeeded" into the same poisoned
 * transaction and the dispatch's commit threw
 * {@code UnexpectedRollbackException} — from the interceptor, AFTER
 * {@code handleVerifiedWebhook}'s body, so the compensating dedup delete
 * never ran and the committed dedup row acknowledged the provider's retry
 * without ever settling the payment (Spring Framework Reference,
 * Declarative Transaction Management: a runtime exception crossing a
 * participating REQUIRED scope marks the whole transaction rollback-only).
 *
 * <p><b>The repository stays REAL (CodeRabbit #458 review round 1):</b> the
 * one-time read failure is injected through a decorating JDK proxy over
 * {@link PaymentIntentRepository} — the {@code @Primary} decorator throws
 * once on the armed {@code findById} call and delegates everything else to
 * the real Spring Data repository. Every state assertion then reads the
 * database FRESH after the dispatch: the intent/payment transitions
 * proving they COMMITTED (JPA's dirty-checking updates a MANAGED entity at
 * flush — the docs' own no-{@code save()} update semantics), not an
 * in-memory mutation. The whole proxied chain is real: webhook signature
 * validation, the dedup recorder (its own REQUIRES_NEW), the dispatch
 * switch, and {@code PaymentIntentSettlementService} with its
 * {@code @Retry}/{@code @Observed}/{@code @Transactional} aspect stack.
 */
@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({ModuleTestConfig.class, WebhookSettlementRetryIntegrationTest.RealChannelConfig.class,
        WebhookSettlementRetryIntegrationTest.ClockBean.class,
        WebhookSettlementRetryIntegrationTest.TransientReadFailureConfig.class})
@WithMockUser
class WebhookSettlementRetryIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String TEST_WEBHOOK_SECRET = "whsec_test_webhook_secret";

    /** The id whose {@code findById} calls the test counts (set per test). */
    static final AtomicReference<UUID> watchedIntentId = new AtomicReference<>();

    /**
     * The one-shot failure arm: set to an intent id to make that id's NEXT
     * {@code findById} call throw the simulated transient failure exactly
     * once (consumed atomically on first hit — later reads delegate to the
     * real repository, exactly the seam a transient infrastructure blip
     * hits in production).
     */
    static final AtomicReference<UUID> failNextFindByIdFor = new AtomicReference<>();

    /** How many {@code findById} calls for the watched id reached the repository layer. */
    static final AtomicInteger watchedFindByIdAttempts = new AtomicInteger();

    @TestConfiguration
    static class RealChannelConfig {
        @Bean
        PspChannel testStripeChannel() {
            return new StripePspChannel("sk_test_inert", TEST_WEBHOOK_SECRET);
        }
    }

    /**
     * The webhook verifier's Clock — the production bean lives in
     * platform-infra's ClockConfig, which this slice does not scan (the
     * ClockConfig javadoc's own "tests override the bean" pattern).
     */
    @TestConfiguration
    static class ClockBean {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    /**
     * The failure-injection decorator: {@code @Primary} so every injection
     * point (the services and this test) sees it, while its delegate is the
     * real Spring Data repository resolved by its bean name through
     * {@code @Qualifier} (the documented qualifier-by-bean-name fallback —
     * the JPA factory registers the interface under this exact name). The
     * proxy throws once on the armed {@code findById} and forwards
     * everything else untouched.
     */
    @TestConfiguration
    static class TransientReadFailureConfig {
        @Bean
        @Primary
        PaymentIntentRepository transientlyFailingIntentRepository(
                @Qualifier("paymentIntentRepository") PaymentIntentRepository real) {
            return (PaymentIntentRepository) Proxy.newProxyInstance(
                    PaymentIntentRepository.class.getClassLoader(),
                    new Class<?>[]{PaymentIntentRepository.class},
                    (proxy, method, args) -> {
                        if ("findById".equals(method.getName()) && args != null && args.length == 1
                                && args[0] instanceof UUID target
                                && target.equals(watchedIntentId.get())) {
                            watchedFindByIdAttempts.incrementAndGet();
                            if (failNextFindByIdFor.compareAndSet(target, null)) {
                                throw new IllegalStateException(
                                        "simulated transient settlement read failure");
                            }
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    @Autowired
    private PaymentsService paymentsService;

    @Autowired
    private PaymentWebhookSecurity paymentWebhookSecurity;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentWebhookEventRepository webhookEventRepository;

    @Test
    void webhookConfirm_firstAttemptFailsThenRetries_settlementCommits() {
        PaymentIntent intent = paymentIntentRepository.save(
                PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null));
        intent.markProcessing();
        intent = paymentIntentRepository.save(intent);
        Payment payment = paymentRepository.save(Payment.create(intent.getId(), 5000L));
        String eventId = "evt_retry_" + UUID.randomUUID();

        UUID intentId = intent.getId();
        watchedIntentId.set(intentId);
        failNextFindByIdFor.set(intentId);

        String signature = paymentWebhookSecurity.computeSignatureHeader("stripe", eventId,
                "payment_intent.succeeded", intentId, "ch_retry",
                Instant.now().getEpochSecond());

        // Through the real proxied webhook path. Under the pre-fix REQUIRED
        // propagation this call itself throws UnexpectedRollbackException at
        // its transaction interceptor (the carrier was poisoned by attempt 1).
        boolean created = paymentsService.processWebhookEvent("stripe", eventId,
                "payment_intent.succeeded", signature, intentId, "ch_retry");

        assertThat(created).as("the dispatch completed — the carrier was never poisoned").isTrue();
        assertThat(watchedFindByIdAttempts.get())
                .as("the retry loop ran the settlement read exactly twice")
                .isEqualTo(2);

        // Fresh database reads — the transitions COMMITTED (managed-entity
        // dirty-checking at the REQUIRES_NEW flush), not in-memory mutations.
        assertThat(paymentIntentRepository.findById(intentId).orElseThrow().getStatus())
                .as("the retried attempt's intent transition committed")
                .isEqualTo(PaymentIntentStatus.SUCCEEDED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .as("the payment row completed and committed with the settlement")
                .isEqualTo(PaymentStatus.COMPLETED);
        assertThat(webhookEventRepository.findByProviderAndEventId("stripe", eventId))
                .as("the dedup row stands behind a dispatched event")
                .isPresent();
    }
}
