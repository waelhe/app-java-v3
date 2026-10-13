package com.marketplace.notifications.push;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 7 (ADR-0003) — the fail-safe fallback's own law: with no FCM
 * credentials bound, every send reports failure WITHOUT pruning (the
 * registry keeps its tokens — an inert provider never falsifies the
 * verdict into an unregistered claim), one result per input token in
 * order, and the channel stays silent about the message itself.
 */
class NoopPushAdapterTest {

    private final NoopPushAdapter adapter = new NoopPushAdapter();

    @Test
    void everyTokenReportsAnUnattemptedFailureInOrder() {
        List<PushNotificationPort.PushResult> results = adapter.send(
                List.of("token-a", "token-b"), "title", "body");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).token()).isEqualTo("token-a");
        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).unregistered()).isFalse();
        assertThat(results.get(1).token()).isEqualTo("token-b");
        assertThat(results.get(1).success()).isFalse();
        assertThat(results.get(1).unregistered()).isFalse();
    }

    @Test
    void anEmptyRegistryCostsNothing() {
        assertThat(adapter.send(List.of(), "title", "body")).isEmpty();
    }
}
