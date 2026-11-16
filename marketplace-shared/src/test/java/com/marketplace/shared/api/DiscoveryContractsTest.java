package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Waves D1-D4: the discovery projection contracts' value semantics — the
 * records the rails speak across every module boundary. The tests pin the
 * three guarantees the plan names and the assembler must keep:
 * {@link DiscoveryRowView} defensively copies its card list (no caller can
 * mutate a rendered rail after the fact), {@link DiscoveryCardView} carries
 * the full projection identity (sourceType + sourceId + updatedAt — the
 * AC-20-10 re-check contract), and {@link DiscoveryRowType} stays the
 * closed six-rail vocabulary (§1.4) — no rail can appear from nowhere.
 */
class DiscoveryContractsTest {

    private static final Instant T = Instant.parse("2026-10-10T00:00:00Z");

    private static final UUID SCOPE = UUID.nameUUIDFromBytes("scope".getBytes());

    private DiscoveryCardView card(String sourceType, UUID sourceId) {
        return new DiscoveryCardView(sourceType, sourceId, T, "عنوان البطاقة", "مقتطف صادق",
                "ACTIVE", SCOPE, "حديث في حيّك", null);
    }

    @Test
    void cardView_carriesTheFullProjectionIdentity() {
        UUID id = UUID.randomUUID();
        DiscoveryCardView card = card("NEIGHBORHOOD_POST", id);
        assertThat(card.sourceType()).isEqualTo("NEIGHBORHOOD_POST");
        assertThat(card.sourceId()).isEqualTo(id);
        assertThat(card.updatedAt()).isEqualTo(T);
        assertThat(card.state()).isEqualTo("ACTIVE");
        assertThat(card.paid()).isNull();
        assertThat(card.reason()).isEqualTo("حديث في حيّك");
    }

    @Test
    void cardView_recordSemantics_hashEqualsAndToString() {
        UUID id = UUID.randomUUID();
        DiscoveryCardView a = card("NEIGHBORHOOD_EVENT", id);
        DiscoveryCardView b = card("NEIGHBORHOOD_EVENT", id);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("NEIGHBORHOOD_EVENT").contains("عنوان البطاقة");
    }

    @Test
    void rowView_defensivelyCopiesTheCardList() {
        List<DiscoveryCardView> mutable = new ArrayList<>();
        mutable.add(card("JOB", UUID.randomUUID()));
        DiscoveryRowView row = new DiscoveryRowView(DiscoveryRowType.EVENTS_AND_OPPORTUNITIES,
                mutable, 7);
        mutable.clear();
        assertThat(row.cards()).hasSize(1);
        assertThat(row.row()).isEqualTo(DiscoveryRowType.EVENTS_AND_OPPORTUNITIES);
        assertThat(row.totalEligible()).isEqualTo(7);
    }

    @Test
    void rowView_recordSemantics_hashEqualsAndToString() {
        DiscoveryRowView a = new DiscoveryRowView(DiscoveryRowType.LOST_FOUND,
                List.of(card("NEIGHBORHOOD_POST", UUID.randomUUID())), 1);
        DiscoveryRowView b = new DiscoveryRowView(DiscoveryRowType.LOST_FOUND,
                List.of(a.cards().get(0)), 1);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("LOST_FOUND");
    }

    @Test
    void paginationFactories_stayCoveredOnTheWaveContractsPath() {
        // The rails page through the shared neutral pair — the factories the
        // discovery ports' adapters speak must keep their value semantics.
        PagedRequest simple = PagedRequest.of(0, 10);
        assertThat(simple.page()).isZero();
        assertThat(simple.size()).isEqualTo(10);
        assertThat(simple.sort()).isEmpty();
        PagedRequest sorted = PagedRequest.of(1, 20, new PagedRequest.Order("createdAt", true));
        assertThat(sorted.sort()).hasSize(1);
        assertThat(sorted.sort().get(0).property()).isEqualTo("createdAt");
        assertThat(sorted.sort().get(0).descending()).isTrue();
        assertThat(PagedResponse.<DiscoveryCardView>empty(simple).content()).isEmpty();
    }

    @Test
    void rowType_isTheClosedSixRailVocabulary() {
        assertThat(DiscoveryRowType.values()).hasSize(6);
        assertThat(DiscoveryRowType.valueOf("URGENT_ALERTS")).isEqualTo(DiscoveryRowType.URGENT_ALERTS);
        assertThat(DiscoveryRowType.valueOf("FOLLOWED_SOURCES")).isEqualTo(DiscoveryRowType.FOLLOWED_SOURCES);
        assertThat(DiscoveryRowType.valueOf("LOST_FOUND")).isEqualTo(DiscoveryRowType.LOST_FOUND);
        assertThat(DiscoveryRowType.valueOf("NEIGHBORHOOD_RECOMMENDATIONS"))
                .isEqualTo(DiscoveryRowType.NEIGHBORHOOD_RECOMMENDATIONS);
        assertThat(DiscoveryRowType.valueOf("EVENTS_AND_OPPORTUNITIES"))
                .isEqualTo(DiscoveryRowType.EVENTS_AND_OPPORTUNITIES);
        assertThat(DiscoveryRowType.valueOf("FOR_YOU")).isEqualTo(DiscoveryRowType.FOR_YOU);
    }
}
