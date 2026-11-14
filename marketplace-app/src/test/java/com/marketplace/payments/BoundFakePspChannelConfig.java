package com.marketplace.payments;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A no-network bound channel for the integration journeys that must walk the
 * REAL money paths (W3: {@code processIntent} now requires a provider — the
 * fifth provider gate answers 503 SU-001 when none is bound, so journeys that
 * plant a paid intent supply their own fake instead of riding the retired
 * inert-local path).
 *
 * <p>Shape follows the {@code PspChannel} javadoc's own contracts:
 * <ul>
 *   <li>{@code createRemoteIntent} remembers the charged amount per remote id
 *       so a later FULL refund ({@code amountCents == null}) can answer the
 *       cumulative remote actual the local books must reflect (roadmap L19
 *       acceptance 3) — the one state this fake keeps;</li>
 *   <li>{@code createRemoteRefund} answers {@code succeeded} with the
 *       CUMULATIVE total refunded on the underlying charge after this refund
 *       (the {@code RemoteRefund} contract's own "cumulative remote total"):
 *       each new idempotency key atomically adds its delta to the running
 *       per-intent total, and the resulting cumulative answer is memoized per
 *       idempotency key so a replayed request replays the SAME total instead
 *       of double-refunding — journeys assert the ledger's REFUND_DEBIT and
 *       REFUNDED terminal state, which the SYNCED branch applies from this
 *       number;</li>
 *   <li>{@code verifyWebhook} is unsupported: the journeys drive the
 *       administrative confirm leg directly and never verify a provider
 *       signature (the webhook-resilience suites own their own channel).</li>
 * </ul>
 */
@TestConfiguration
public class BoundFakePspChannelConfig {

    /**
     * {@code @Primary} settles the bean election when a test context ALSO
     * binds a real channel (Stripe credentials set): the journeys' bound
     * fake must win over the network-touching candidate, or the Spring
     * context itself fails to start on bean ambiguity — the fake is the
     * declared winner either way.
     */
    @Bean
    @Primary
    PspChannel boundFakePspChannel() {
        return new BoundFakePspChannel();
    }

    static final class BoundFakePspChannel implements PspChannel {
        private final Map<String, Long> chargedAmountsByPspIntentId = new ConcurrentHashMap<>();
        private final Map<String, Long> refundedTotalsByPspIntentId = new ConcurrentHashMap<>();
        private final Map<String, Long> refundResultsByIdempotencyKey = new ConcurrentHashMap<>();

        @Override
        public RemoteIntent createRemoteIntent(UUID marketplaceIntentId, long amountCents,
                                               String currency, String idempotencyKey) {
            String pspIntentId = "pi_fake_" + marketplaceIntentId;
            chargedAmountsByPspIntentId.put(pspIntentId, amountCents);
            return new RemoteIntent(pspIntentId, "cs_fake_" + marketplaceIntentId);
        }

        @Override
        public RemoteRefund createRemoteRefund(String pspIntentId, Long amountCents,
                                                String idempotencyKey) {
            // The RemoteRefund contract carries the CUMULATIVE total refunded
            // on the underlying charge after this refund — the remote actual
            // the local books must reflect. A replayed idempotency key
            // memoizes its recorded cumulative answer (computeIfAbsent is the
            // atomic-per-key gate): a retry never adds its delta twice. A
            // null amount refunds the REMAINING balance, so the cumulative
            // total lands on the whole charge (never charge-on-top-of-partials).
            long cumulative = refundResultsByIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
                long charge = chargedAmountsByPspIntentId.getOrDefault(pspIntentId, 0L);
                if (amountCents == null) {
                    return refundedTotalsByPspIntentId.merge(pspIntentId, charge, Math::max);
                }
                return refundedTotalsByPspIntentId.merge(pspIntentId, amountCents, Long::sum);
            });
            return new RemoteRefund("re_fake_" + pspIntentId, "succeeded", cumulative);
        }

        @Override
        public VerifiedWebhook verifyWebhook(String rawPayload, String signatureHeader) {
            throw new UnsupportedOperationException(
                    "the integration journeys never verify provider signatures through the fake channel");
        }
    }
}
