package com.marketplace.community;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L51 — the membership entity's own join shape (the EventRsvp entity
 * test convention verbatim): the two id facts land on their columns,
 * the id is a fresh UUID, and the row carries no authored text at all
 * (the reaction row's and the seat's own class — the fact IS the data).
 */
class NeighborhoodGroupMembershipTest {

    @Test
    void join_carriesTheTwoIdFactsAndAFreshId() {
        UUID groupId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        NeighborhoodGroupMembership membership = NeighborhoodGroupMembership.join(groupId, memberId);

        assertThat(membership.getId()).isNotNull();
        assertThat(membership.getGroupId()).isEqualTo(groupId);
        assertThat(membership.getMemberId()).isEqualTo(memberId);
    }

    @Test
    void join_twiceForTheSamePair_yieldsTwoDistinctRows() {
        UUID groupId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        NeighborhoodGroupMembership first = NeighborhoodGroupMembership.join(groupId, memberId);
        NeighborhoodGroupMembership second = NeighborhoodGroupMembership.join(groupId, memberId);

        // The one-membership law lives in the SERVICE (the explicit 409)
        // and the V95 partial unique index — never in the factory, which
        // stays the honest insert shape (the V73/V83 discipline verbatim:
        // a fresh seat is free for a fresh join after a leave).
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }
}
