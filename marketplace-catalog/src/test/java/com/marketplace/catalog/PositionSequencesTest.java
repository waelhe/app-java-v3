package com.marketplace.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 2026-11-07 #808 round-19 review (the overflow family): the shared
 * position-derivation law's own known-vector tests — the exact boundaries
 * the review proved (the REST surfaces accept Integer.MAX_VALUE) and the
 * free-window parking the compaction path needs.
 */
class PositionSequencesTest {

    @Test
    void appendPositionDerivesMaxPlusOne() {
        assertThat(PositionSequences.appendPosition(0)).isEqualTo(OptionalInt.of(1));
        assertThat(PositionSequences.appendPosition(41)).isEqualTo(OptionalInt.of(42));
    }

    @Test
    void appendPositionAnswersEmptyAtTheCeiling() {
        // The exact wrap the round-19 finding proved: a stored
        // Integer.MAX_VALUE position makes max+1 negative — the shared
        // law answers empty (the caller's classified refusal).
        assertThat(PositionSequences.appendPosition(Integer.MAX_VALUE)).isEmpty();
        assertThat(PositionSequences.appendPosition(Integer.MAX_VALUE - 1))
                .isEqualTo(OptionalInt.of(Integer.MAX_VALUE));
    }

    @Test
    void parkBaseParksAboveTheStoredMaximumWhenTheRangeFits() {
        // The documented two-phase law: max(maxHeld, size) + 1 — the
        // parked range sits strictly above both the stored positions and
        // the final dense range.
        assertThat(PositionSequences.parkBase(List.of(1, 2, 3), 4)).isEqualTo(5);
        assertThat(PositionSequences.parkBase(List.of(0, 1), 2)).isEqualTo(3);
        assertThat(PositionSequences.parkBase(List.of(7), 1)).isEqualTo(8);
    }

    @Test
    void parkBaseFindsTheLowestFreeWindowWhenTheCeilingBlocksTheNaturalBase() {
        // A ceiling-high stored position (the REST registerOption surface
        // accepts one): the natural max+1 base wraps negative — the scan
        // takes the lowest free window at or above the dense range's top.
        // Positions [1, 2, MAX], size 3: the window [4, 6] is the lowest
        // free range at or above size+1 = 4.
        assertThat(PositionSequences.parkBase(List.of(1, 2, Integer.MAX_VALUE), 3)).isEqualTo(4);
        // Positions [1, 2, 3, 4, MAX], size 5: [4, 8] collides with the
        // held 4 — the first free window at or above 6 is [6, 10].
        assertThat(PositionSequences.parkBase(List.of(1, 2, 3, 4, Integer.MAX_VALUE), 5)).isEqualTo(6);
    }

    @Test
    void parkBaseKeepsTheWindowClearOfEveryHeldPosition() {
        // The window walk skips past EACH held position it meets — the
        // parked range never shares a seat with a not-yet-moved row.
        // Held {2, 4, 6}, size 3: starts at 4 (blocked by 4), then 5
        // (window [5,7] meets 6), then 7 — the window [7, 9] is free.
        assertThat(PositionSequences.parkBase(List.of(2, 4, 6), 3)).isEqualTo(7);
    }

    @Test
    void parkBaseParksAContiguousTopBlockInTheFreeSpaceBelow() {
        // The dense-at-the-ceiling shape: positions [MAX-2, MAX-1, MAX],
        // size 3 — no room above, and the whole dense range's top is 4;
        // the window [4, 6] is free (the held block sits at the top).
        assertThat(PositionSequences.parkBase(
                List.of(Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 1, Integer.MAX_VALUE), 3))
                .isEqualTo(4);
    }

    @Test
    void parkBaseAnswersNegativeOnlyWhenNoWindowExists() {
        // A pathological full block: every candidate start is held —
        // unreachable through any real bucket (n held positions block at
        // most n candidate windows), but the contract stays total.
        assertThat(PositionSequences.parkBase(List.of(1), 1)).isEqualTo(2);
        // An empty held set still parks above the dense range's top —
        // the natural law (max(0, size) + 1), never a window inside it.
        assertThat(PositionSequences.parkBase(List.of(), 1)).isEqualTo(2);
    }
}
