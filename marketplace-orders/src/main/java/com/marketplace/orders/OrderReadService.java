package com.marketplace.orders;

import com.marketplace.orders.spi.OrderPage;
import com.marketplace.orders.spi.OrderReadSpi;
import com.marketplace.orders.spi.OrderSort;
import com.marketplace.orders.spi.OrderView;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * P4 (the canonical Saleor plan §5-P4 — the Saleor {@code order} and
 * {@code orders} read roots, packet campaign-b5-b): the owning use
 * case for the store's order population's read surface. These are
 * Saleor MANAGEMENT roots — "Requires one of the following
 * permissions: MANAGE_ORDERS" — mapped through the platform's ADMIN
 * capability (the SaleorPermissionCatalog contract) and enforced HERE
 * at the owning use-case boundary by Spring Security's method
 * security (the same {@code @PreAuthorize} mechanism the identity
 * module's staff-user reads established — the plan's own
 * "check permissions at service/use-case boundaries" rule), never
 * only in a resolver and never in a UI route guard. There is no
 * public population on this root: a non-ADMIN caller answers the
 * authorization failure; the buyer's own-order surface is the
 * User.orders field's own later slice, never this root.
 *
 * <p><b>The scroll is a REAL SQL keyset scroll</b> (the
 * {@code scrollStaffUsers} repository-keyset precedent): the derived
 * {@code Window}-returning repository methods with the static
 * ordering baked into the method name and the {@code id} tiebreaker —
 * Hibernate's own keyset predicate and {@code @SoftDelete} filter
 * carry the whole contract. The STATUS sort's keyset position carries
 * the caller's cursor value as text (the wire's JSON form); THIS use
 * case rebinds it as the entity's own {@link OrderStatus} vocabulary
 * before the scroll so the framework's keyset predicate binds the
 * column's own type — the gate normalized the names, the owner
 * normalizes the vocabulary.</p>
 */
@Service
public class OrderReadService implements OrderReadSpi {

    private final OrderRepository orderRepository;

    public OrderReadService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * The order lookup by the native UUID behind the typed global ID —
     * ADMIN-enforced at this boundary: an unknown but well-formed UUID
     * answers empty (the safe absence); a soft-deleted row is absent by
     * the entity's own {@code @SoftDelete} filter.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Optional<OrderView> findOrderById(UUID id, Authentication authentication) {
        if (id == null) {
            return Optional.empty();
        }
        return orderRepository.findById(id)
                .map(OrderReadService::toView);
    }

    /**
     * The order population's REAL SQL forward-only keyset scroll — the
     * whole live order population (the ADMIN-only management surface),
     * in the requested owning sort with the {@code id} tiebreaker. The
     * page's total is the same population's count.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public OrderPage scrollOrders(Authentication authentication,
                                  OrderSort sort,
                                  KeysetScrollPosition position,
                                  Limit limit) {
        if (sort == null || sort.field() == null) {
            throw new IllegalArgumentException("An order sort is required");
        }
        if (limit == null || limit.max() <= 0) {
            throw new IllegalArgumentException("A positive page size is required");
        }
        KeysetScrollPosition scrolled = sort.field() == OrderSort.Field.STATUS
                ? rebindStatusKey(position)
                : position;
        Window<Order> window = switch (sort.field()) {
            case CREATED_AT -> sort.ascending()
                    ? orderRepository.findByOrderByPlacedAtAscIdAsc(scrolled, limit)
                    : orderRepository.findByOrderByPlacedAtDescIdDesc(scrolled, limit);
            case LAST_MODIFIED_AT -> sort.ascending()
                    ? orderRepository.findByOrderByUpdatedAtAscIdAsc(scrolled, limit)
                    : orderRepository.findByOrderByUpdatedAtDescIdDesc(scrolled, limit);
            case STATUS -> sort.ascending()
                    ? orderRepository.findByOrderByStatusAscIdAsc(scrolled, limit)
                    : orderRepository.findByOrderByStatusDescIdDesc(scrolled, limit);
        };
        return new OrderPage(window.map(OrderReadService::toView), orderRepository.count());
    }

    /**
     * The STATUS sort's keyset rebind: the decoded cursor carries the
     * status key as its JSON text form; the framework's keyset predicate
     * binds the entity's own enum type, so the position is rebuilt with
     * the {@link OrderStatus} value — an unparseable value answers the
     * same classified validation family the boundary's guards answer
     * (never a silent fallback to another position).
     */
    private static KeysetScrollPosition rebindStatusKey(KeysetScrollPosition position) {
        if (position.isInitial()) {
            return position;
        }
        Map<String, Object> keys = position.getKeys();
        Object raw = keys.get("status");
        if (raw instanceof OrderStatus) {
            return position;
        }
        try {
            OrderStatus status = OrderStatus.valueOf(String.valueOf(raw));
            return (KeysetScrollPosition) ScrollPosition.forward(Map.of(
                    "status", status,
                    "id", keys.get("id")));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "after must be a forward keyset cursor emitted by orders with the same sortBy",
                    exception);
        }
    }

    /**
     * The bounded projection — only facts the {@code orders} row owns:
     * the native key, the placement instant, and the lifecycle state
     * name (the platform's own vocabulary — the boundary's mapper
     * renders it through Saleor's own OrderStatus vocabulary).
     */
    private static OrderView toView(Order order) {
        return new OrderView(order.getId(), order.getPlacedAt(), order.getStatus().name());
    }
}
