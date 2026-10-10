package com.marketplace.search;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.UnifiedSearchHit;
import com.marketplace.shared.api.UnifiedSearchQuery;
import com.marketplace.shared.api.UnifiedSearchResponse;
import com.marketplace.shared.api.UnifiedSearchSource;
import com.marketplace.shared.api.UnifiedSearchSourcePort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 5 (community platform execution plan — the unified legal
 * multi-domain search): the orchestrator's own guards — the deterministic
 * source order (injection order never leaks into the answer), the safe
 * degradation (a failing source degrades to absence, never fails the
 * whole answer), and the measured consultation (every source lands on the
 * meter registry). The parity and visibility gates ride the real
 * PostgreSQL in the app's {@code UnifiedSearchParityIntegrationTest}.
 */
class UnifiedSearchServiceTest {

    private static UnifiedSearchHit hit(UnifiedSearchSource source, String title) {
        return new UnifiedSearchHit(source, UUID.randomUUID(), title, "", null, "/x/" + title);
    }

    @Test
    void mergesInDeterministicSourceOrder_regardlessOfInjectionOrder() {
        UnifiedSearchSourcePort knowledge = new Stub(UnifiedSearchSource.KNOWLEDGE,
                hit(UnifiedSearchSource.KNOWLEDGE, "k1"));
        UnifiedSearchSourcePort posts = new Stub(UnifiedSearchSource.COMMUNITY_POST,
                hit(UnifiedSearchSource.COMMUNITY_POST, "p1"));
        // Knowledge is injected FIRST — the answer still groups posts before
        // knowledge (the declaration order, sorted by name).
        UnifiedSearchService service = new UnifiedSearchService(
                List.of(knowledge, posts), new SimpleMeterRegistry());

        UnifiedSearchResponse response = service.search(new UnifiedSearchQuery("q", null, 5));

        assertThat(response.hits()).extracting(UnifiedSearchHit::source)
                .containsExactly(UnifiedSearchSource.COMMUNITY_POST, UnifiedSearchSource.KNOWLEDGE);
        assertThat(response.consultedSources()).containsExactly(
                UnifiedSearchSource.COMMUNITY_POST, UnifiedSearchSource.KNOWLEDGE);
        assertThat(response.degradedSources()).isEmpty();
    }

    @Test
    void aFailingSourceDegradesToAbsence_andTheAnswerStaysWhole() {
        UnifiedSearchSourcePort broken = new Stub(UnifiedSearchSource.INSTITUTION) {
            @Override
            public List<UnifiedSearchHit> search(UnifiedSearchQuery query) {
                throw new IllegalStateException("registry down");
            }
        };
        UnifiedSearchSourcePort posts = new Stub(UnifiedSearchSource.COMMUNITY_POST,
                hit(UnifiedSearchSource.COMMUNITY_POST, "p1"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UnifiedSearchService service = new UnifiedSearchService(
                List.of(posts, broken), registry);

        UnifiedSearchResponse response = service.search(new UnifiedSearchQuery("q", null, 5));

        assertThat(response.degradedSources()).containsExactly(UnifiedSearchSource.INSTITUTION);
        assertThat(response.consultedSources()).containsExactly(
                UnifiedSearchSource.COMMUNITY_POST, UnifiedSearchSource.INSTITUTION);
        assertThat(response.hits()).hasSize(1);
        // The degradation is measured, never silent — the plan's «قياس» gate.
        assertThat(registry.counter("search.unified.degraded", "source", "INSTITUTION").count())
                .isEqualTo(1.0);
    }

    @Test
    void theQueryContractPinsTheVocabularyBeforeAnyStoreIsTouched() {
        assertThatThrownBy(() -> new UnifiedSearchQuery("   ", null, 5))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new UnifiedSearchQuery("q", null, 0))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new UnifiedSearchQuery("q", null, 21))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * One source answering a fixed list — the orchestrator's shape under
     * test (the adapters' own contracts ride their modules' repositories).
     */
    private static class Stub implements UnifiedSearchSourcePort {
        private final UnifiedSearchSource source;
        private final List<UnifiedSearchHit> hits;

        Stub(UnifiedSearchSource source, UnifiedSearchHit... hits) {
            this.source = source;
            this.hits = List.of(hits);
        }

        @Override
        public UnifiedSearchSource source() {
            return source;
        }

        @Override
        public List<UnifiedSearchHit> search(UnifiedSearchQuery query) {
            return hits;
        }
    }
}
