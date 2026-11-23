package com.marketplace.orders.spi;

import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * B27 (ORD-08 — the 2026-11-03 adversarial review, review-pr-811): the
 * orders module's implementation of the {@link AuthoredContentPurgePort}
 * cross-module contract. Purges the destination snapshot's ADDRESS of the
 * orders the subject <em>placed</em> — the write path is measured:
 * {@code consumer_id} is the order's buyer, so {@code consumer_id = ?} is
 * exactly the subject's authored set. The order row itself, its status
 * history and its financial columns stay (Art. 17(3)(b) / 20(4) — the
 * plan's §4 keeps the shared accounting record).
 *
 * <p><b>Schema facts (measured, V224):</b> {@code orders.ship_to_address}
 * is {@code varchar(250)} nullable — but the
 * {@code orders_fulfillment_shape_chk} constraint requires it NON-EMPTY
 * exactly when {@code fulfillment_method = 'PLATFORM_DELIVERY'} (the frozen
 * selection's shape law), so the nullable-NULL convention is impossible on
 * the platform-delivery rows: the purge writes the shared
 * {@link AuthoredContentPurgePort#PURGED_MARKER} tombstone — the
 * {@code disputes.reason} precedent verbatim (the marker is what the
 * counterparty sees on the shared record, the declared Art. 20(4)
 * trade-off made visible). PICKUP and legacy orders already carry NULL and
 * the idempotence filter skips them. {@code orders_aud} mirrors
 * {@code consumer_id} and {@code ship_to_address} (V224's mirror block), so
 * the mirror purges with the same predicate.</p>
 *
 * <p><b>The retained destination data (the recorded retention decision the
 * finding demanded):</b> {@code ship_to_latitude}/{@code ship_to_longitude}
 * STAY. The shape constraint requires the coordinates exactly when it
 * requires the address (a platform delivery always carries a valid frozen
 * destination), and the numeric columns admit no marker — a sentinel
 * coordinate would be fabricated geolocation, not erasure. The frozen
 * coordinates are part of the fulfillment record the platform must keep
 * for the delivery's own audit (Art. 17(3)(b)); the ADDRESS — the free-text
 * personal datum the finding names — is the purged surface. The
 * fulfillment method and the shipping amount stay untouched: they are the
 * frozen commercial agreement, never the subject's authored text.</p>
 *
 * <p><b>The seller's own cancel reasons (the 2026-11-23 #808 review's
 * fourth finding, closed at the root):</b> the port's contract binds this
 * module to purge EVERY free text the subject authored — and
 * {@code cancelForSeller} records {@code cancel_author = 'SELLER'}
 * (V303's closed vocabulary) precisely so this purge can tell. A seller's
 * reasons on OTHER consumers' orders are the SELLER's own authored words
 * on the shared commercial record: the seller's erasure owns them, exactly
 * as the buyer's own reasons answer the buyer's purge. Matched by the
 * order's {@code seller_id} linkage (V184 — the A1 users.id space) AND the
 * author, never by {@code consumer_id}; the live row advances
 * {@code version} (the adapter's standing optimistic-lock law); the
 * CONSUMER-authored and legacy-NULL reasons of the SAME rows stay, and
 * ADMIN-authored reasons stay everywhere (an administrative account is an
 * operational actor, not an order party — the (2-b) authorship wall's
 * symmetric completion).</p>
 *
 * <p><b>The terminal-completion leg (the 2026-11-23 #808 review's third
 * finding, closed at the root):</b> the address purge EXCLUDES in-flight
 * deliveries ({@code PLACED}/{@code CONFIRMED}) because erasing the frozen
 * destination mid-flight breaks the fulfillment — the adapter's own comment
 * promised "a later purge run after the order reaches FULFILLED or
 * CANCELLED", but no mechanism ever fired that later run (the port only ran
 * through the admin's UserService path). {@code OrdersTerminalPurgeListener}
 * IS that mechanism: the module's own terminal events drive
 * {@link #purgeTerminalOrderAddress(UUID)} for THAT order once the
 * transition commits — the admin-path statements below and the
 * event-driven leg are idempotent together (the marker filter makes every
 * overlap a zero-row no-op).</p>
 *
 * <p><b>Statement shape (the port's contract):</b> native JDBC UPDATE,
 * idempotent by the {@code IS NOT NULL AND <> marker} filter — exact
 * counts, zero matches on re-run; no Envers revision for the purge itself
 * (the orchestrator's structured log line is the audit record).</p>
 *
 * <p><b>The optimistic-lock advance (the standing adapter law, agent
 * findings 2026-10-26 / CWE-362):</b> the live row's UPDATE also advances
 * {@code version} — the {@code @Version} column every audited entity
 * carries — so a lifecycle transaction holding a pre-purge managed order
 * snapshot flushes zero rows and fails with the framework's
 * optimistic-lock exception instead of restoring the erased address into
 * the live row and a new Envers revision.</p>
 */
@Component
public class OrdersContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(OrdersContentPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public OrdersContentPurgeAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        // The 2026-11-05 #811 round (Major · Security & Privacy), two
        // scope corrections on the measured schema:
        //
        // (1) The address purge EXCLUDES the orders that still need their
        // delivery — the Art. 17(3)(b) contract-fulfillment ground the
        // adapter itself already cites for the retained commercial
        // record: a PLACED/CONFIRMED platform-delivery order's frozen
        // address is the delivery's own destination, and erasing it
        // mid-flight breaks the fulfillment. Those rows (and their audit
        // history) keep the address until the order reaches FULFILLED or
        // CANCELLED — where the module's own terminal events now fire
        // OrdersTerminalPurgeListener.purgeTerminalOrderAddress (the
        // 2026-11-23 #808 third finding's completion leg).
        //
        // (2) The subject's authored texts now include the buyer's own
        // cancel reason (orders.cancel_reason, always pure authored text
        // on a CANCELLED row) and the return REASON (order_returns.reason
        // — the buyer's own words, never a delivery input). The returns'
        // resolution_note STAYS: it is the merchant decision record
        // (reject/cancel notes — the shared commercial record the
        // retention decisions already keep), not the subject's authored
        // text; the buyer-authored reason is the purged surface.
        //
        // (2-b) The AUTHORSHIP wall (the 2026-11-09 candidate F1 finding,
        // P2 — Security & Privacy / Data Integrity): V303/V304's
        // cancel_author exists precisely so the purge can tell WHO
        // wrote the cancel reason, and this adapter is the column's
        // intended reader. A reason authored by the SELLER
        // (cancelForSeller) or an ADMIN (cancelAsAdmin/bulkCancelAsAdmin)
        // on a row whose consumer_id is the BUYER is a third party's
        // authored text on the shared commercial record — the buyer's
        // erasure right never owned it (the same retention decision the
        // returns' resolution_note already carries). The buyer's purge
        // keeps SELLER/ADMIN reasons — live rows AND audit mirrors alike
        // — and erases only the CONSUMER-authored reasons plus the legacy
        // NULL population (V303's own comment: the pre-attribution rows
        // keep the standing purge behavior — the buyer-facing self-cancel
        // was the platform's dominant path).
        //
        // (3) The SELLER's own reasons (the 2026-11-23 #808 review's
        // fourth finding): the port's contract covers every free text the
        // subject authored in THIS module — the seller's cancel reasons
        // on their OWN sales (seller_id = the subject, cancel_author =
        // 'SELLER') are exactly that. The statements below match the
        // seller linkage AND the author, never consumer_id; the buyer's
        // reasons on the same rows and every ADMIN-authored reason stay
        // (each subject's erasure owns only their own words).
        int ordersAddress = jdbcTemplate.update(
                "UPDATE orders SET ship_to_address = ?, version = version + 1 "
                        + "WHERE consumer_id = ? AND ship_to_address IS NOT NULL "
                        + "AND ship_to_address <> ? "
                        + "AND status NOT IN ('PLACED', 'CONFIRMED')",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int ordersAddressAudit = jdbcTemplate.update(
                "UPDATE orders_aud SET ship_to_address = ? WHERE consumer_id = ? "
                        + "AND ship_to_address IS NOT NULL AND ship_to_address <> ? "
                        + "AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = orders_aud.id "
                        + "AND o.status IN ('PLACED', 'CONFIRMED'))",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int ordersCancelReason = jdbcTemplate.update(
                "UPDATE orders SET cancel_reason = ?, version = version + 1 "
                        + "WHERE consumer_id = ? AND cancel_reason IS NOT NULL "
                        + "AND cancel_reason <> ? "
                        + "AND (cancel_author IS NULL OR cancel_author = 'CONSUMER')",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int ordersCancelReasonAudit = jdbcTemplate.update(
                "UPDATE orders_aud SET cancel_reason = ? WHERE consumer_id = ? "
                        + "AND cancel_reason IS NOT NULL AND cancel_reason <> ? "
                        + "AND (cancel_author IS NULL OR cancel_author = 'CONSUMER')",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        // (3) the seller's own words on their own sales — seller_id (V184,
        // mirrored to orders_aud in the same migration block) AND the
        // author (V303/V304); the live row advances version like every
        // other statement of this adapter.
        int sellerCancelReason = jdbcTemplate.update(
                "UPDATE orders SET cancel_reason = ?, version = version + 1 "
                        + "WHERE seller_id = ? AND cancel_reason IS NOT NULL "
                        + "AND cancel_reason <> ? AND cancel_author = 'SELLER'",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int sellerCancelReasonAudit = jdbcTemplate.update(
                "UPDATE orders_aud SET cancel_reason = ? WHERE seller_id = ? "
                        + "AND cancel_reason IS NOT NULL AND cancel_reason <> ? "
                        + "AND cancel_author = 'SELLER'",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int returnsReason = jdbcTemplate.update(
                "UPDATE order_returns SET reason = ?, version = version + 1 "
                        + "WHERE consumer_id = ? AND reason IS NOT NULL AND reason <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int returnsReasonAudit = jdbcTemplate.update(
                "UPDATE order_returns_aud SET reason = ? WHERE consumer_id = ? "
                        + "AND reason IS NOT NULL AND reason <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        log.info("Orders content purge: userId={}, ordersAddress={}, ordersAddressAudit={}, "
                        + "ordersCancelReason={}, ordersCancelReasonAudit={}, sellerCancelReason={}, "
                        + "sellerCancelReasonAudit={}, returnsReason={}, returnsReasonAudit={}",
                userId, ordersAddress, ordersAddressAudit, ordersCancelReason,
                ordersCancelReasonAudit, sellerCancelReason, sellerCancelReasonAudit,
                returnsReason, returnsReasonAudit);
        return ordersAddress + ordersAddressAudit + ordersCancelReason + ordersCancelReasonAudit
                + sellerCancelReason + sellerCancelReasonAudit
                + returnsReason + returnsReasonAudit;
    }

    /**
     * The terminal-completion leg (the 2026-11-23 #808 review's third
     * finding): the ONE-order targeted purge the module's own terminal
     * events drive — {@code OrdersTerminalPurgeListener} calls this the
     * moment an order's transition to FULFILLED or CANCELLED commits, so
     * an address the admin-path purge held back (the in-flight delivery
     * exclusion) is erased as soon as the retention ground lapses, with
     * no operator re-run. Idempotent by the marker filter; the live row
     * advances {@code version} (the standing optimistic-lock law); the
     * audit mirror follows the adapter's NOT EXISTS in-flight guard so a
     * redelivered event on a re-placed row (an impossibility under the
     * terminal-state machine, held defensively) is a zero-row no-op.
     */
    @Transactional
    public int purgeTerminalOrderAddress(UUID orderId) {
        int live = jdbcTemplate.update(
                "UPDATE orders SET ship_to_address = ?, version = version + 1 "
                        + "WHERE id = ? AND status IN ('FULFILLED', 'CANCELLED') "
                        + "AND ship_to_address IS NOT NULL AND ship_to_address <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, orderId, AuthoredContentPurgePort.PURGED_MARKER);
        int audit = jdbcTemplate.update(
                "UPDATE orders_aud SET ship_to_address = ? WHERE id = ? "
                        + "AND ship_to_address IS NOT NULL AND ship_to_address <> ? "
                        + "AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = ? "
                        + "AND o.status IN ('PLACED', 'CONFIRMED'))",
                AuthoredContentPurgePort.PURGED_MARKER, orderId, AuthoredContentPurgePort.PURGED_MARKER,
                orderId);
        if (live + audit > 0) {
            log.info("Terminal-order address purge: orderId={}, liveRows={}, auditRows={}",
                    orderId, live, audit);
        }
        return live + audit;
    }
}
