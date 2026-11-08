package com.marketplace.catalog;

import java.util.Collection;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The position-column family's own derivation law (the 2026-11-07 #808
 * round-19 review, the overflow family): every registry that numbers its
 * rows with a non-negative {@code int} position derives the next value or
 * a rewrite's parking range from the STORED maximum — and a stored
 * {@code Integer.MAX_VALUE} (the REST surfaces that accept a
 * client-supplied position with only a {@code @Min(0)} guard make it
 * reachable for attribute values and type-attribute assignments) wraps
 * that derivation to a negative number, answering the database's own
 * {@code CHECK (position >= 0)} failure instead of a classified result.
 *
 * <p>Two derivations live here, shared by every callsite so the law is
 * ONE implementation, never per-service arithmetic:</p>
 *
 * <ul>
 *   <li>{@link #appendPosition(int)} — the append's {@code max + 1},
 *       empty when the bucket sits at the ceiling (the caller answers
 *       its own classified refusal naming the compaction path).</li>
 *   <li>{@link #parkBase(Collection, int)} — the two-phase reorder's
 *       parking base: the natural {@code max(max, size) + 1} when the
 *       range fits under the ceiling, else the LOWEST free window at or
 *       above the dense rewrite's own top ({@code size + 1}) — a window
 *       that avoids every currently-held position keeps phase 1's
 *       intermediate state unique against the live partial index, and
 *       staying at or above {@code size + 1} keeps the final dense
 *       {@code 1..n} range strictly below the parked rows (the
 *       flush-order law the original park comment documents). The
 *       reorder IS the compaction path: it must succeed WITH a
 *       ceiling-high position present, never strand the bucket.</li>
 * </ul>
 */
final class PositionSequences {

    private PositionSequences() {
    }

    /**
     * The append position after the given stored maximum — empty when the
     * maximum sits at the {@code int} ceiling (the caller's own
     * classified refusal names the reorder's compaction path).
     */
    static OptionalInt appendPosition(int storedMax) {
        return storedMax == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(storedMax + 1);
    }

    /**
     * The two-phase rewrite's parking base for a bucket of {@code size}
     * live rows holding the given positions — negative when no legal
     * window exists (the caller answers its own classified refusal).
     *
     * <p>The natural base parks the whole bucket above its stored
     * maximum. When that range would overflow the ceiling, the scan
     * walks upward from the dense range's own top and takes the first
     * window of {@code size} consecutive positions no live row holds:
     * at most {@code size} positions are held, so the scan terminates
     * in at most {@code size} blocked candidates for any realistic
     * bucket.</p>
     */
    static int parkBase(Collection<Integer> heldPositions, int size) {
        if (size <= 0) {
            // An empty bucket rewrites nothing — the caller's own
            // no-change probe owns that path; a base of 1 keeps the
            // contract total without a window.
            return 1;
        }
        int maxHeld = 0;
        for (int held : heldPositions) {
            maxHeld = Math.max(maxHeld, held);
        }
        int natural = Math.max(maxHeld, size) + 1;
        // The whole parked range [natural, natural + size - 1] must fit
        // under the ceiling — otherwise the overflow this family closes
        // happens on the very first reposition.
        if (maxHeld < Integer.MAX_VALUE && natural <= Integer.MAX_VALUE - size + 1) {
            return natural;
        }
        Set<Integer> held = new HashSet<>(heldPositions);
        // The 2026-11-08 #808 round-21 review (Minor · Stability &
        // Availability): the scan rides a LONG cursor — at the boundary
        // window (start == Integer.MAX_VALUE - size + 1) the previous
        // {@code position < start + size} bound overflowed to a negative
        // int, examined NO position, and returned the window unchecked
        // (an occupied window — the unique index answered the reorder
        // mid-rewrite), and a blocker AT the ceiling wrapped its
        // {@code position + 1} jump to Integer.MIN_VALUE (a negative
        // base — the DB CHECK's own failure). The long cursor keeps
        // every bound and every jump in range: the window is always
        // examined in full, the jump past a ceiling blocker exits the
        // loop, and the contract stays total (never an occupied window,
        // never a negative base).
        long start = size + 1L;
        long lastLegalStart = (long) Integer.MAX_VALUE - size + 1;
        while (start <= lastLegalStart) {
            boolean free = true;
            long nextStart = start;
            for (int offset = 0; offset < size; offset++) {
                int position = (int) (start + offset);
                if (held.contains(position)) {
                    nextStart = position + 1L;
                    free = false;
                    break;
                }
            }
            if (free) {
                return (int) start;
            }
            start = nextStart;
        }
        return -1;
    }
}
