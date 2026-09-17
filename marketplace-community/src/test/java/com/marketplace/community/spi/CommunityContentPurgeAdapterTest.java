package com.marketplace.community.spi;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L41 — the documented purge exception (the plan's own reasoned
 * decision): the membership row is keys and state — no authored free
 * text exists to NULL or tombstone, so the adapter reports zero by
 * design and the row's retention stays b-5's decision. The integration
 * test proves the same against the real schema: the row survives a
 * purge call byte-identically.
 */
class CommunityContentPurgeAdapterTest {

    @Test
    void purgeAuthoredTexts_isZeroByDesign_andIdempotent() {
        CommunityContentPurgeAdapter adapter = new CommunityContentPurgeAdapter();
        UUID userId = UUID.randomUUID();

        assertThat(adapter.purgeAuthoredTexts(userId)).isZero();
        assertThat(adapter.purgeAuthoredTexts(userId)).isZero();
    }
}
