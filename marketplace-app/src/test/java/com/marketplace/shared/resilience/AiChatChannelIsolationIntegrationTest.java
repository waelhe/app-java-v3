package com.marketplace.shared.resilience;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import test.config.IntegrationContainers;

import com.marketplace.ai.AiChatGateway;
import com.marketplace.shared.api.ServiceUnavailableException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * D.4 (compliance plan wave D) — the AI provider channel's
 * <b>isolation gate</b> («صمود القنوات الخارجية», the Resilience4j engine
 * the compliance plan's reference ladder names). The provider is bound
 * through a mocked {@link ChatModel} whose {@code call} refuses everything —
 * a provider outage in exactly the shape the gateway's production call path
 * sees (the {@code ObjectProvider<ChatModel>} seam resolves the mock the
 * same way it resolves a real provider's model).
 *
 * <p><b>The measured contract</b> (decorator composition measured from the
 * Resilience4j bytecode: Retry aspect order 2147483642 wraps CircuitBreaker's
 * 2147483643 — Retry OUTER, CircuitBreaker INNER; each failed call's retry
 * attempts count into the breaker window individually, so with max-attempts
 * 2 two failed calls fill the compressed 4-call window at 100% failure):</p>
 * <ol>
 *   <li>Provider failures count into the {@code aiChat} circuit — a
 *       sustained outage OPENS it, and the next call fails FAST with
 *       {@link CallNotPermittedException} (the guard's own signature — the
 *       provider mock is never touched again).</li>
 *   <li>The OFF state stays honest: {@code ServiceUnavailableException} (the
 *       503 SU-001 "capability is OFF, not broken" contract) is pinned into
 *       the instance's {@code ignore-exceptions} — a state is not a channel
 *       failure and must never open the circuit. The pin is asserted from
 *       the live instance's own {@link CircuitBreakerConfig} predicate.</li>
 * </ol>
 *
 * <p>Thresholds are test-compressed via {@code properties} (the production
 * posture stays the yml base-config); the state machine under proof is the
 * very same one.</p>
 */
@SpringBootTest(properties = {
        // The compressed aiChat circuit: 4-call window, 50% threshold.
        "resilience4j.circuitbreaker.instances.aiChat.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.aiChat.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.aiChat.failure-rate-threshold=50",
        "resilience4j.circuitbreaker.instances.aiChat.wait-duration-in-open-state=60s",
        // The retry stays in the loop but compressed: 2 attempts, no backoff wait.
        "resilience4j.retry.instances.aiChat.max-attempts=2",
        "resilience4j.retry.instances.aiChat.wait-duration=10ms",
        "resilience4j.retry.instances.aiChat.enable-exponential-backoff=false",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AiChatChannelIsolationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    /** The bound provider seam — the outage itself (every call refused). */
    @MockitoBean
    private ChatModel chatModel;

    @Autowired
    private AiChatGateway aiChatGateway;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Test
    void sustainedProviderOutageIsIsolatedByTheCircuit() {
        CircuitBreaker aiChat = circuitBreakers.circuitBreaker("aiChat");

        // The provider outage: the bound seam misbehaves — every completion
        // attempt fails with the provider's own error (an idempotent read —
        // retrying it is the designed transient-blip absorption; each attempt
        // counts into the breaker). Lenient by design: whichever exact failure
        // surface the ChatClient stack surfaces first, the breaker's window
        // counts attempts — the assertions below are state-based.
        lenient().when(chatModel.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("provider outage simulation"));

        // Phase 1 — two failed calls: each retried once (max-attempts 2),
        // four breaker-window samples, 100% failure.
        for (int i = 0; i < 2; i++) {
            int attempt = i + 1;
            assertThatThrownBy(() -> aiChatGateway.chat("isolation gate " + attempt))
                    .as("chat call %d against the dead provider fails honestly", attempt)
                    .isInstanceOf(RuntimeException.class)
                    .isNotInstanceOf(ServiceUnavailableException.class);
        }

        // Phase 2 — THE isolation: the circuit is OPEN, and the next call is
        // refused BEFORE the provider: fail fast with the guard's own
        // signature, never a hung request thread.
        assertThat(aiChat.getState())
                .as("the sustained provider outage crossed the failure-rate threshold — aiChat is OPEN")
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> aiChatGateway.chat("isolation gate — isolated"))
                .as("an OPEN circuit refuses the call without touching the provider")
                .isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void theOffStateNeverOpensTheCircuit() {
        // The honest-OFF pin, asserted from the LIVE instance's own config:
        // ServiceUnavailableException (503 SU-001 — "capability is OFF, not
        // broken") rides the ignore-exceptions list of BOTH the circuit and
        // its retry, so an OFF deployment's honest 503s are invisible to the
        // breaker — the circuit reports the PROVIDER's health only. The
        // predicate is the live behavioral truth (not the raw yml text).
        CircuitBreakerConfig config = circuitBreakers.circuitBreaker("aiChat").getCircuitBreakerConfig();

        assertThat(config.getIgnoreExceptionPredicate()
                .test(new ServiceUnavailableException("The AI chat capability is OFF (predicate check)")))
                .as("the OFF state's 503 is ignored by the aiChat circuit — a state, not a channel failure")
                .isTrue();
    }
}
