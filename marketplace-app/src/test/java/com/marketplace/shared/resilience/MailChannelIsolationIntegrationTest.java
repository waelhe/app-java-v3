package com.marketplace.shared.resilience;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import test.config.IntegrationContainers;

import com.marketplace.shared.email.EmailSendException;
import com.marketplace.shared.email.EmailService;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D.4 (compliance plan wave D) — the SMTP channel's <b>isolation gate</b>
 * («صمود القنوات الخارجية», the Resilience4j engine the compliance plan's
 * reference ladder names). The scenario under proof is a sustained mail
 * outage, driven through the REAL production wiring: the auto-configured
 * {@code JavaMailSender} (the test profile's standing {@code spring.mail.host=
 * localhost, port=3025} binding) with <b>no GreenMail listening</b> — nothing
 * accepts the connection on 3025, which is precisely an SMTP outage for this
 * deployment shape.
 *
 * <p><b>The measured contract</b> (the decorator composition is measured
 * fact from the Resilience4j bytecode: Retry's aspect order 2147483642
 * wraps CircuitBreaker's 2147483643 — Retry OUTER, CircuitBreaker INNER —
 * so each failed send's retry attempts count into the breaker window
 * individually; with max-attempts 2, two failed sends fill the compressed
 * 4-call window at 100% failure):</p>
 * <ol>
 *   <li>{@code @Retry(name = "mailSend")} absorbs the transient shape —
 *       every failed send retried per the instance config (compressed to
 *       max-attempts 2 here), proven through the retry registry's own
 *       metrics.</li>
 *   <li>{@code @CircuitBreaker(name = "mailSend")} isolates the sustained
 *       shape — the failure rate crosses the threshold (window compressed to
 *       4 calls here), the circuit OPENS, and a further send fails FAST with
 *       {@link CallNotPermittedException}: the guard refused BEFORE the
 *       channel was touched. The exception type is the isolation's own
 *       signature — only the breaker's guard throws it.</li>
 *   <li>The degradation is the A-04/A-13 contract, unchanged in shape: the
 *       send fails (the registry publication stays incomplete for the
 *       framework's resubmission), never a silent success, never a hang.</li>
 * </ol>
 *
 * <p>The thresholds are test-compressed via {@code properties} so the gate
 * runs in seconds; the production posture stays the yml base-config (10-call
 * window, 50%, 60s open wait) — the state machine under proof is the very
 * same one.</p>
 */
@SpringBootTest(properties = {
        // The compressed mailSend circuit: 4-call window, 50% threshold.
        "resilience4j.circuitbreaker.instances.mailSend.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.mailSend.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.mailSend.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.mailSend.wait-duration-in-open-state=60s",
        // The retry stays in the loop but compressed: 2 attempts, no backoff wait.
        "resilience4j.retry.instances.mailSend.max-attempts=2",
        "resilience4j.retry.instances.mailSend.wait-duration=10ms",
        "resilience4j.retry.instances.mailSend.enable-exponential-backoff=false",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class MailChannelIsolationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private EmailService emailService;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Autowired
    private RetryRegistry retries;

    @Test
    void sustainedSmtpOutageIsRetriedThenIsolatedByTheCircuit() {
        CircuitBreaker mailSend = circuitBreakers.circuitBreaker("mailSend");
        Retry retry = retries.retry("mailSend");

        // Phase 1 — the sustained outage: nothing listens on the test
        // profile's SMTP port; every send fails (after its retry pass) with
        // the channel's own EmailSendException. Two such sends = four
        // breaker-window samples (retry attempts count individually — the
        // measured composition), which is the compressed window full at
        // 100% failure.
        for (int i = 0; i < 2; i++) {
            int attempt = i + 1;
            assertThatThrownBy(() -> emailService.send(
                    "neighbor@example.local", "Isolation gate " + attempt,
                    "email/password-reset", Map.of()))
                    .as("send attempt %d against the dead SMTP channel fails honestly", attempt)
                    .isInstanceOf(EmailSendException.class);
        }

        // The retry machinery did its transient-blip duty on the way: both
        // failed calls went through their retry pass (the official
        // Retry.Metrics counter — the same feed the platform's metrics
        // export reads).
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt())
                .as("each failed send was retried before surfacing (the transient-blip absorption)")
                .isGreaterThanOrEqualTo(2);

        // Phase 2 — THE isolation: the circuit is OPEN...
        assertThat(mailSend.getState())
                .as("the sustained outage crossed the failure-rate threshold — mailSend is OPEN")
                .isEqualTo(CircuitBreaker.State.OPEN);

        // ...and the next send is refused BEFORE the channel: fail fast, no
        // connect-timeout hang on the async listener's thread.
        assertThatThrownBy(() -> emailService.send(
                "neighbor@example.local", "Isolation gate — isolated",
                "email/password-reset", Map.of()))
                .as("an OPEN circuit refuses the send without touching the channel")
                .isInstanceOf(CallNotPermittedException.class);

        // The degraded state is observable by the platform's own feeds — the
        // circuit-breaker metrics the alerting stack scrapes (paymentProcessing
        // already rides the same registry).
        assertThat(mailSend.getMetrics().getNumberOfFailedCalls())
                .as("the outage's failed calls are visible to the metrics feed")
                .isGreaterThanOrEqualTo(4);
    }
}
