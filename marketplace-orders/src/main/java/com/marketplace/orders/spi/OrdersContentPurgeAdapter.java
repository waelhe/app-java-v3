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
        int orders = jdbcTemplate.update(
                "UPDATE orders SET ship_to_address = ?, version = version + 1 "
                        + "WHERE consumer_id = ? AND ship_to_address IS NOT NULL "
                        + "AND ship_to_address <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int auditRows = jdbcTemplate.update(
                "UPDATE orders_aud SET ship_to_address = ? WHERE consumer_id = ? "
                        + "AND ship_to_address IS NOT NULL AND ship_to_address <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        log.info("Orders content purge: userId={}, orders={}, auditRows={}", userId, orders, auditRows);
        return orders + auditRows;
    }
}
