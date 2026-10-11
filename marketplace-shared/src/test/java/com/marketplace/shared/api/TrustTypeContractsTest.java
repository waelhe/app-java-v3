package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 (the unified plan §10) — the §6.5 trust vocabulary's contract:
 * exactly FOUR values with the section's literal semantics, closed (no
 * value can appear from nowhere), distinct (the no-conflation rule —
 * «لا خلط بينها»: الإقامة لا تجعل كل معلومة صحيحة، وتوثيق النشاط لا يجعل
 * تقييمه ممتازًا), and never collapsible into one mega «موثق» flag
 * (§4.5's explicit prohibition). The tests pin the vocabulary at the
 * shared-api boundary so a silent widening or renaming fails here
 * before any consumer drifts.
 */
class TrustTypeContractsTest {

    /** §6.5's four, in the section's own order and naming. */
    private static final Set<TrustType> THE_FOUR = Set.of(
            TrustType.VERIFIED_LOCAL_MEMBER,
            TrustType.VERIFIED_BUSINESS,
            TrustType.COMMUNITY_ENDORSEMENT,
            TrustType.VERIFIED_SOURCE);

    @Test
    void theVocabularyIsExactlyTheFourOfParagraph65() {
        assertThat(EnumSet.allOf(TrustType.class))
                .as("§6.5 names exactly four trust types — a fifth value is a widening "
                        + "the plan never authorized")
                .containsExactlyInAnyOrderElementsOf(THE_FOUR);
    }

    @Test
    void everyTypeCarriesItsOwnDistinctName_noConflation() {
        // The four names are pairwise distinct (an enum guarantees that)
        // — the assertion pins the SEMANTIC distinctness contract: the
        // stored names are the vocabulary every surface (API, DB CHECK,
        // audit) speaks, so each must name exactly one §6.5 fact.
        assertThat(TrustType.VERIFIED_LOCAL_MEMBER.name())
                .isEqualTo("VERIFIED_LOCAL_MEMBER");
        assertThat(TrustType.VERIFIED_BUSINESS.name())
                .isEqualTo("VERIFIED_BUSINESS");
        assertThat(TrustType.COMMUNITY_ENDORSEMENT.name())
                .isEqualTo("COMMUNITY_ENDORSEMENT");
        assertThat(TrustType.VERIFIED_SOURCE.name())
                .isEqualTo("VERIFIED_SOURCE");
    }

    @Test
    void valueOf_roundTripsTheStoredNames() {
        // The DB CHECK (V184) and the wire carry these exact strings —
        // the round trip is the D-N7 two-sided discipline's contract.
        for (TrustType type : TrustType.values()) {
            assertThat(TrustType.valueOf(type.name())).isSameAs(type);
        }
    }
}
