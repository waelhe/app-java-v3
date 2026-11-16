package com.marketplace.community;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L51 — the group entity's own founding shape (the NeighborhoodEvent /
 * NeighborhoodMarketItem entity-test convention verbatim): the three
 * authored facts land on their columns, the id is a fresh UUID, and
 * the aggregate carries no more than the registered contract
 * (name/description/members — the members count is a READ fact the
 * service derives, never a stored column).
 */
class NeighborhoodGroupTest {

    @Test
    void founded_carriesTheThreeAuthoredFactsAndAFreshId() {
        UUID locationId = UUID.randomUUID();

        NeighborhoodGroup group = NeighborhoodGroup.founded(locationId,
                "فريق دراجي ومشي النخيل", "تجمّع يومي 5:30 فجراً");

        assertThat(group.getId()).isNotNull();
        assertThat(group.getLocationId()).isEqualTo(locationId);
        assertThat(group.getName()).isEqualTo("فريق دراجي ومشي النخيل");
        assertThat(group.getDescription()).isEqualTo("تجمّع يومي 5:30 فجراً");
    }

    @Test
    void founded_twiceInOneNeighborhood_yieldsTwoDistinctRows() {
        UUID locationId = UUID.randomUUID();

        NeighborhoodGroup first = NeighborhoodGroup.founded(locationId, "أ", "و");
        NeighborhoodGroup second = NeighborhoodGroup.founded(locationId, "أ", "و");

        // Two same-named clubs of one hood are two rows — the house has
        // no title uniqueness anywhere (posts, events, market items),
        // and the board's complete sort key keeps them deterministically
        // apart.
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }
}
