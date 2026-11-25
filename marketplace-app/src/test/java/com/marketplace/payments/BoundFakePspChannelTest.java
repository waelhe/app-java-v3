package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Primary;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link BoundFakePspChannelConfig} fake's own contract fidelity
 * (CodeRabbit round on #827, 2026-11-14 — two of its three actionable
 * comments): the fake must honor the {@link PspChannel} javadoc contracts it
 * claims to follow, or the integration journeys it carries assert the real
 * money paths against a lying double.
 *
 * <p>Pinned behavior:
 * <ul>
 *   <li>{@code createRemoteRefund} answers the CUMULATIVE total refunded on
 *       the underlying charge after this refund (the {@code RemoteRefund}
 *       record's own "cumulative remote total" — the remote actual the local
 *       books must reflect), not the last request's amount;</li>
 *   <li>a replayed idempotency key replays the SAME recorded cumulative
 *       answer — a retry never adds its delta twice;</li>
 *   <li>a null amount (refund the remaining balance) after partial refunds
 *       lands on the whole charge — never charge-on-top-of-partials;</li>
 *   <li>the bean carries {@code @Primary}: when a test context also binds a
 *       real channel (Stripe credentials set), the fake must win the bean
 *       election or the context itself fails to start on ambiguity.</li>
 * </ul>
 */
class BoundFakePspChannelTest {

    private final BoundFakePspChannelConfig.BoundFakePspChannel channel =
            new BoundFakePspChannelConfig.BoundFakePspChannel();

    private String charge(UUID intentId, long amountCents) {
        return channel.createRemoteIntent(intentId, amountCents, "SAR", "ik_" + intentId)
                .pspIntentId();
    }

    @Test
    void twoConsecutivePartialRefundsAnswerTheCumulativeRemoteTotal() {
        UUID intentId = UUID.randomUUID();
        String pspIntentId = charge(intentId, 1000L);

        PspChannel.RemoteRefund first = channel.createRemoteRefund(pspIntentId, 300L, "rk_1");
        PspChannel.RemoteRefund second = channel.createRemoteRefund(pspIntentId, 200L, "rk_2");

        assertThat(first.refundedTotalCents()).as("first partial refund: its own amount").isEqualTo(300L);
        assertThat(second.refundedTotalCents())
                .as("second partial refund: the cumulative remote actual (300 + 200), not the last request's 200")
                .isEqualTo(500L);
        assertThat(first.status()).isEqualTo("succeeded");
        assertThat(second.status()).isEqualTo("succeeded");
    }

    @Test
    void replayedIdempotencyKeyReplaysTheSameCumulativeTotal() {
        UUID intentId = UUID.randomUUID();
        String pspIntentId = charge(intentId, 1000L);

        PspChannel.RemoteRefund first = channel.createRemoteRefund(pspIntentId, 300L, "rk_same");
        PspChannel.RemoteRefund retried = channel.createRemoteRefund(pspIntentId, 300L, "rk_same");
        PspChannel.RemoteRefund nextNew = channel.createRemoteRefund(pspIntentId, 200L, "rk_next");

        assertThat(retried.refundedTotalCents())
                .as("a replayed request replays the recorded cumulative answer — no double refund")
                .isEqualTo(first.refundedTotalCents())
                .isEqualTo(300L);
        assertThat(nextNew.refundedTotalCents())
                .as("the next NEW key still adds onto the true cumulative total (300 + 200)")
                .isEqualTo(500L);
    }

    @Test
    void nullAmountAfterPartialRefundsLandsOnTheWholeCharge() {
        UUID intentId = UUID.randomUUID();
        String pspIntentId = charge(intentId, 1000L);

        channel.createRemoteRefund(pspIntentId, 300L, "rk_partial");
        PspChannel.RemoteRefund remaining = channel.createRemoteRefund(pspIntentId, null, "rk_rest");

        assertThat(remaining.refundedTotalCents())
                .as("null amount refunds the remaining balance: the cumulative total is the whole charge")
                .isEqualTo(1000L);

        PspChannel.RemoteRefund fullFirst = chargeAndRefundFully();
        assertThat(fullFirst.refundedTotalCents())
                .as("a null amount on an un-refunded charge answers the whole charge")
                .isEqualTo(1000L);
    }

    private PspChannel.RemoteRefund chargeAndRefundFully() {
        UUID intentId = UUID.randomUUID();
        String pspIntentId = charge(intentId, 1000L);
        return channel.createRemoteRefund(pspIntentId, null, "rk_full");
    }

    @Test
    void theBoundFakeBeanIsPrimarySoItWinsTheElectionAgainstARealChannel() throws Exception {
        Method bean = BoundFakePspChannelConfig.class.getDeclaredMethod("boundFakePspChannel");
        Primary primary = bean.getAnnotation(Primary.class);

        assertThat(primary)
                .as("the journeys' bound fake must win the bean election when a test context "
                        + "also binds a real channel (Stripe credentials set) — otherwise the "
                        + "context fails to start on bean ambiguity")
                .isNotNull();
    }
}
