package com.marketplace.orders;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part-2 review round 1 (the aggregate's own integration cover): the
 * overlap guard's ONE fixed aggregate — {@code sumConsumedByOrderItemId}
 * (row 6, CWE-400) — executed against the REAL Flyway schema, never the
 * unit tests' hand-seeded projection rows. The finding's exact shape:
 * a JPQL projection binding error (alias/column mismatch), a status-set
 * mistake, or a cross-order leak in the subselect would all let the
 * guard ACCEPT a return for units another return already consumed —
 * none of which a mock can see.
 *
 * <p>The seeded history: one order carrying a COMPLETED return (A:3,
 * B:2) and a REQUESTED one (A:1) — both counted; a REJECTED return
 * (A:1) and a CANCELLED one (B:1) — both excluded (the counted set's
 * own vocabulary: only REQUESTED through COMPLETED keep their units);
 * and a SECOND ORDER's return holding one of THIS order's item lines —
 * excluded by the subselect's {@code r.orderId = :orderId} leg, the
 * cross-order isolation the guard's restriction promises.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class OrderReturnOverlapAggregateIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches LeadsIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    private static final Instant T0 = Instant.parse("2026-10-26T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-26T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-26T12:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-26T13:00:00Z");

    private static final List<OrderReturn.ReturnStatus> COUNTED = List.of(
            OrderReturn.ReturnStatus.REQUESTED, OrderReturn.ReturnStatus.APPROVED,
            OrderReturn.ReturnStatus.RECEIVED, OrderReturn.ReturnStatus.COMPLETED);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderReturnLineRepository returnLines;

    @Test
    void theAggregateSumsOnlyThisOrdersCountedReturnLines() {
        UUID buyer = UUID.randomUUID();
        UUID orderOne = UUID.randomUUID();
        UUID orderTwo = UUID.randomUUID();
        UUID itemA = UUID.randomUUID();
        UUID itemB = UUID.randomUUID();
        UUID itemC = UUID.randomUUID();

        seedUser(buyer);
        seedOrder(orderOne, buyer);
        seedOrder(orderTwo, buyer);
        seedItem(itemA, orderOne, 5);
        seedItem(itemB, orderOne, 3);
        seedItem(itemC, orderTwo, 2);

        UUID completedReturn = UUID.randomUUID();
        seedReturn(completedReturn, orderOne, buyer, "COMPLETED", 10_000L, T0, T1, T2, T3);
        seedLine(completedReturn, itemA, 3);
        seedLine(completedReturn, itemB, 2);

        UUID requestedReturn = UUID.randomUUID();
        seedReturn(requestedReturn, orderOne, buyer, "REQUESTED", null, T0, null, null, null);
        seedLine(requestedReturn, itemA, 1);

        UUID rejectedReturn = UUID.randomUUID();
        seedReturn(rejectedReturn, orderOne, buyer, "REJECTED", null, T0, null, null, null);
        seedLine(rejectedReturn, itemA, 1);

        UUID cancelledReturn = UUID.randomUUID();
        seedReturn(cancelledReturn, orderOne, buyer, "CANCELLED", null, T0, null, null, null);
        seedLine(cancelledReturn, itemB, 1);

        // The second order's COMPLETED return — its OWN line (C:2) plus a
        // line that carries THIS order's item A: the aggregate for orderOne
        // must never count either (the subselect's orderId leg).
        UUID foreignReturn = UUID.randomUUID();
        seedReturn(foreignReturn, orderTwo, buyer, "COMPLETED", 10_000L, T0, T1, T2, T3);
        seedLine(foreignReturn, itemC, 2);
        seedLine(foreignReturn, itemA, 1);

        // The guard's own call shape — the full counted vocabulary.
        Map<UUID, Long> consumed = returnLines
                .sumConsumedByOrderItemId(orderOne, List.of(itemA, itemB), COUNTED).stream()
                .collect(Collectors.toMap(ConsumedReturnQuantity::getOrderItemId,
                        ConsumedReturnQuantity::getConsumedQuantity));

        assertThat(consumed)
                .as("A: COMPLETED 3 + REQUESTED 1; B: COMPLETED 2 — the REJECTED/CANCELLED "
                        + "units and the other order's line are never counted")
                .containsOnly(
                        Map.entry(itemA, 4L),
                        Map.entry(itemB, 2L));

        // The status set is a parameter, not a hardcode — COMPLETED alone
        // must answer the completed history alone.
        Map<UUID, Long> completedOnly = returnLines
                .sumConsumedByOrderItemId(orderOne, List.of(itemA, itemB),
                        List.of(OrderReturn.ReturnStatus.COMPLETED)).stream()
                .collect(Collectors.toMap(ConsumedReturnQuantity::getOrderItemId,
                        ConsumedReturnQuantity::getConsumedQuantity));
        assertThat(completedOnly).containsOnly(
                Map.entry(itemA, 3L),
                Map.entry(itemB, 2L));

        // The item restriction — one item answers one row.
        Optional<ConsumedReturnQuantity> onlyA = returnLines
                .sumConsumedByOrderItemId(orderOne, List.of(itemA), COUNTED).stream()
                .findFirst();
        assertThat(onlyA).hasValueSatisfying(row -> {
            assertThat(row.getOrderItemId()).isEqualTo(itemA);
            assertThat(row.getConsumedQuantity()).isEqualTo(4L);
        });

        // An order with no return history answers nothing — the guard's
        // cold-start path.
        assertThat(returnLines.sumConsumedByOrderItemId(UUID.randomUUID(),
                List.of(itemA, itemB), COUNTED)).isEmpty();
    }

    private void seedUser(UUID id) {
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                """, id, "overlap-" + id, "overlap-" + id + "@example.com", "overlap guard test");
    }

    private void seedOrder(UUID id, UUID buyer) {
        jdbc.update("""
                INSERT INTO orders (id, consumer_id, status, placed_at, total_amount_minor, currency)
                VALUES (?, ?, 'FULFILLED', ?, 50_000, 'SAR')
                """, id, buyer, java.sql.Timestamp.from(T0));
    }

    private void seedItem(UUID id, UUID orderId, int quantity) {
        jdbc.update("""
                INSERT INTO order_items (id, order_id, product_id, quantity, unit_amount_minor, currency)
                VALUES (?, ?, ?, ?, 10_000, 'SAR')
                """, id, orderId, UUID.randomUUID(), quantity);
    }

    private void seedReturn(UUID id, UUID orderId, UUID buyer, String status,
                            Long refundMinor, Instant requested, Instant approved,
                            Instant received, Instant completed) {
        jdbc.update("""
                INSERT INTO order_returns (id, order_id, consumer_id, return_type, status, reason,
                        refund_amount_minor, currency, requested_at, approved_at, received_at, completed_at,
                        version, created_at, updated_at)
                VALUES (?, ?, ?, 'RETURN', ?, 'overlap guard aggregate cover', ?, 'SAR', ?, ?, ?, ?,
                        0, now(), now())
                """, id, orderId, buyer, status, refundMinor,
                java.sql.Timestamp.from(requested),
                approved == null ? null : java.sql.Timestamp.from(approved),
                received == null ? null : java.sql.Timestamp.from(received),
                completed == null ? null : java.sql.Timestamp.from(completed));
    }

    private void seedLine(UUID returnId, UUID itemId, int quantity) {
        jdbc.update("""
                INSERT INTO order_return_lines (id, order_return_id, order_item_id, product_id,
                        quantity, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, now(), now())
                """, UUID.randomUUID(), returnId, itemId, UUID.randomUUID(), quantity);
    }
}
