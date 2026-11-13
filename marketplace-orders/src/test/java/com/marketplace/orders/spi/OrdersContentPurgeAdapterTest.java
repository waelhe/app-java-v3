package com.marketplace.orders.spi;

import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B27 (ORD-08 — the 2026-11-03 adversarial review, review-pr-811): the
 * orders purge adapter's statement-shape contract under test — the same
 * proofs the standing adapters carry:
 *
 * <ul>
 *   <li>the LIVE statement writes the PURGED_MARKER tombstone (the
 *       {@code orders_fulfillment_shape_chk} law requires a non-empty
 *       address exactly on the platform-delivery rows — the
 *       {@code disputes.reason} precedent), advances the live row's
 *       optimistic-lock {@code version} atomically with the erasure, and
 *       keeps the authorship predicate {@code consumer_id} with the
 *       idempotence filter {@code IS NOT NULL AND <> marker};</li>
 *   <li>the AUDIT statement purges the mirrored address WITHOUT touching
 *       any version column — {@code orders_aud} rows are immutable history
 *       keyed by revision;</li>
 *   <li>the LATITUDE/LONGITUDE columns stay untouched — the recorded
 *       retention decision (the frozen fulfillment record's own audit,
 *       Art. 17(3)(b); the numeric columns admit no marker, and a sentinel
 *       coordinate would be fabricated geolocation, not erasure).</li>
 * </ul>
 */
class OrdersContentPurgeAdapterTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private OrdersContentPurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new OrdersContentPurgeAdapter(jdbcTemplate);
    }

    @Test
    void purgeAuthoredTexts_advancesTheLiveRowVersionAtomicallyWithTheErasure() {
        when(jdbcTemplate.update(contains("UPDATE orders SET ship_to_address"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(3);

        adapter.purgeAuthoredTexts(SUBJECT);

        // The tombstone + the optimistic-lock advance ride the SAME single
        // statement — atomic by construction, and the shape constraint's
        // non-empty-address law stays satisfied on the platform-delivery
        // rows.
        verify(jdbcTemplate).update(
                contains("UPDATE orders SET ship_to_address = ?, version = version + 1"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));
    }

    @Test
    void purgeAuthoredTexts_scrubsTheAuditMirrorWithoutTouchingAnyVersionColumn() {
        when(jdbcTemplate.update(contains("UPDATE orders SET ship_to_address"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE orders_aud SET ship_to_address"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(5);

        int purged = adapter.purgeAuthoredTexts(SUBJECT);

        verify(jdbcTemplate).update(
                contains("UPDATE orders_aud SET ship_to_address = ? WHERE consumer_id = ?"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));
        assertEquals(5, purged);
    }

    @Test
    void purgeAuthoredTexts_neverTouchesTheFrozenCoordinatesOrTheCommercialColumns() {
        when(jdbcTemplate.update(contains("UPDATE orders"), eq(AuthoredContentPurgePort.PURGED_MARKER),
                eq(SUBJECT), eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE orders_aud"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(0);

        adapter.purgeAuthoredTexts(SUBJECT);

        // The retained destination data (the recorded retention decision):
        // latitude, longitude, fulfillment_method and shipping_amount_minor
        // appear in NEITHER statement — only the address column purges,
        // live row AND audit mirror alike.
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql != null && sql.contains("ship_to_address")
                                && !sql.contains("ship_to_latitude")
                                && !sql.contains("ship_to_longitude")
                                && !sql.contains("fulfillment_method")
                                && !sql.contains("shipping_amount_minor")),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));
    }

    /**
     * The 2026-11-05 #811 round's two scope corrections: the address
     * purge EXCLUDES the orders that still need their delivery (a
     * PLACED/CONFIRMED platform-delivery row's frozen address is the
     * delivery's own destination — Art. 17(3)(b)), and the subject's
     * authored texts now include the buyer's cancel reason and the
     * return REASON, live rows AND audit mirrors alike (the returns'
     * resolution_note stays — the merchant decision record).
     */
    @Test
    void purgeAuthoredTexts_excludesStillDeliveringOrdersAndPurgesTheAuthoredReasons() {
        adapter.purgeAuthoredTexts(SUBJECT);

        // The address statements carry the delivery-still-needed
        // exclusion — the live row by its own status, the audit mirror
        // by the live row's current status.
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql != null && sql.contains("UPDATE orders SET ship_to_address")
                                && sql.contains("status NOT IN ('PLACED', 'CONFIRMED')")),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql != null && sql.contains("UPDATE orders_aud SET ship_to_address")
                                && sql.contains("NOT EXISTS")
                                && sql.contains("o.status IN ('PLACED', 'CONFIRMED')")),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));

        // The buyer's cancel reason purges — live (with the version
        // advance) and audit mirror — but ONLY the buyer-authored (or
        // legacy NULL) population: the AUTHORSHIP wall (the 2026-11-09
        // candidate F1 finding) pins the predicate on both statements —
        // SELLER/ADMIN reasons are a third party's authored text on the
        // shared commercial record and stay (the same retention decision
        // resolution_note already carries).
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql != null && sql.contains("UPDATE orders SET cancel_reason = ?, version = version + 1")
                                && sql.contains("(cancel_author IS NULL OR cancel_author = 'CONSUMER')")),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql != null && sql.contains("UPDATE orders_aud SET cancel_reason = ?")
                                && sql.contains("(cancel_author IS NULL OR cancel_author = 'CONSUMER')")),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));

        // The return REASON purges — live (with the version advance) and
        // audit mirror; the resolution_note column appears NOWHERE.
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql != null && sql.contains("UPDATE order_returns SET reason = ?, version = version + 1")
                                && !sql.contains("resolution_note")),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql != null && sql.contains("UPDATE order_returns_aud SET reason = ?")
                                && !sql.contains("resolution_note")),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));
    }

    /**
     * The F1 regression leg (the 2026-11-09 candidate finding, P2): the
     * seller/admin-authored cancel reasons on the buyer's rows are a
     * THIRD PARTY's authored text — the erasure right being exercised
     * belongs to the buyer alone, so the retention predicate must guard
     * the live statement AND the audit mirror alike. A statement that
     * purges by consumer_id alone (the pre-F1 shape) erases the seller's
     * own words under the buyer's erasure — this leg pins that the
     * authorship predicate is present on BOTH cancel_reason statements,
     * so the defect cannot silently return.
     */
    @Test
    void purgeAuthoredTexts_retainsSellerAndAdminAuthoredCancelReasonsOnBothStatements() {
        adapter.purgeAuthoredTexts(SUBJECT);

        // Every cancel_reason-carrying statement the adapter issues must
        // carry the authorship retention predicate — the live row's
        // statement and the audit mirror's statement alike.
        org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql != null && sql.contains("cancel_reason")
                                && sql.contains("(cancel_author IS NULL OR cancel_author = 'CONSUMER')")),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));

        // And the counterpart law: NO cancel_reason statement may purge by
        // consumer_id alone — the pre-F1 shape (an UPDATE touching
        // cancel_reason with NO cancel_author predicate anywhere) is the
        // exact defect this leg exists to catch.
        org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.never()).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql != null && sql.contains("cancel_reason")
                                && !sql.contains("cancel_author")),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));
    }
}
