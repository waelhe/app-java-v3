package com.marketplace.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Stage 10 (ADR-0006) — the metrics' own unit gate: NDCG@10's official
 * formula (the logarithmic discount) and MRR's first-relevant rule,
 * computed by hand against a fixed ranking. The learned-ordering gate is
 * only as honest as these numbers.
 */
class OrderingEvalMetricsTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID noise = UUID.randomUUID();

    private OrderingEvalCase label(UUID listingId, int relevance) {
        return OrderingEvalCase.label("q", listingId, relevance, "");
    }

    @Test
    void ndcgIsOneWhenTheIdealOrderIsObserved() {
        // Labeled: a=3, b=2, c=1; ranked: a, b, c — the ideal order itself.
        double ndcg = OrderingEvalService.ndcgAt10(
                List.of(label(a, 3), label(b, 2), label(c, 1)),
                List.of(a, b, c, noise));
        assertThat(ndcg).isCloseTo(1.0, within(0.0001));
    }

    @Test
    void ndcgFallsWhenThePerfectHitSinks() {
        // The same labels; the perfect hit ranked 3rd — the discount bites.
        double ndcg = OrderingEvalService.ndcgAt10(
                List.of(label(a, 3), label(b, 2), label(c, 1)),
                List.of(c, b, a, noise));
        assertThat(ndcg).isLessThan(1.0);
        assertThat(ndcg).isGreaterThan(0.0);
    }

    @Test
    void ndcgIsZeroWhenNothingRelevantIsRanked() {
        double ndcg = OrderingEvalService.ndcgAt10(
                List.of(label(a, 3)), List.of(noise));
        assertThat(ndcg).isEqualTo(0.0);
    }

    @Test
    void mrrRewardsTheFirstUsefulHit() {
        // relevance ≥ 2 counts as useful: b (2) at rank 2 → 0.5.
        double mrr = OrderingEvalService.mrr(
                List.of(label(a, 3), label(b, 2)),
                List.of(noise, b, a));
        assertThat(mrr).isEqualTo(0.5);
    }

    @Test
    void mrrIsZeroWhenNoUsefulHitAppears() {
        double mrr = OrderingEvalService.mrr(
                List.of(label(a, 1)), List.of(noise, a));
        assertThat(mrr).isEqualTo(0.0);
    }

    @Test
    void theCaseFactoryGuardsTheGradedScale() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OrderingEvalCase.label("q", a, 4, ""));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OrderingEvalCase.label("q", a, -1, ""));
    }
}
