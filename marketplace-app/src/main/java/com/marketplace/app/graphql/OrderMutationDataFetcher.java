package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Order;
import com.marketplace.orders.spi.OrderCommandSpi;
import com.marketplace.orders.spi.OrderView;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saleor P4 order management commands backed by the existing orders owner.
 * This adapter owns wire IDs and mutation payloads only. The domain service
 * owns ADMIN authorization and the transactional state transition; payment
 * settlement remains with the existing payments event listener.
 */
@DgsComponent
public class OrderMutationDataFetcher {

    private final OrderCommandSpi orderCommandSpi;
    private final OrderGraphQlMapper mapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public OrderMutationDataFetcher(OrderCommandSpi orderCommandSpi,
                                    OrderGraphQlMapper mapper,
                                    SaleorGlobalIdCodec globalIdCodec) {
        this.orderCommandSpi = orderCommandSpi;
        this.mapper = mapper;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Confirm a Saleor order through the existing order lifecycle. The native
     * payments owner, not this transport adapter, remains authoritative for
     * PSP settlement.
     */
    @DgsMutation(field = "orderConfirm")
    public Map<String, Object> orderConfirm(@InputArgument String id) {
        orderCommandSpi.requireAdmin();
        final UUID orderId;
        try {
            orderId = globalIdCodec.decode(id, OrderGraphQlMapper.ORDER_TYPE);
        } catch (IllegalArgumentException exception) {
            return payload(null, List.of(orderError("id", "Invalid global ID", "INVALID")));
        }

        try {
            OrderView confirmed = orderCommandSpi.confirmAsAdmin(orderId);
            return payload(mapper.toGraphQlOrder(confirmed), List.of());
        } catch (ResourceNotFoundException exception) {
            return payload(null, List.of(orderError("id", exception.getMessage(), "NOT_FOUND")));
        } catch (ConflictException exception) {
            return payload(null, List.of(orderError("id", exception.getMessage(), "INVALID")));
        }
    }

    /**
     * Bulk cancellation follows Saleor's count-plus-errors payload. All IDs
     * are decoded before the owner is called, so malformed input cannot cause
     * a partial set of money-moving state transitions.
     */
    @DgsMutation(field = "orderBulkCancel")
    public Map<String, Object> orderBulkCancel(@InputArgument List<String> ids) {
        // Permission is decided before any caller-supplied ID is decoded, and
        // before an empty request can return a success-shaped payload.
        orderCommandSpi.requireAdmin();
        List<String> submittedIds = ids == null ? List.of() : ids;
        final List<UUID> orderIds = new ArrayList<>(submittedIds.size());
        for (String id : submittedIds) {
            try {
                orderIds.add(globalIdCodec.decode(id, OrderGraphQlMapper.ORDER_TYPE));
            } catch (IllegalArgumentException exception) {
                return bulkPayload(0, List.of(orderError(
                        "id", "Invalid global ID", "INVALID")));
            }
        }

        com.marketplace.orders.spi.OrderBulkCancelResult outcome =
                orderCommandSpi.bulkCancelAsAdmin(orderIds);
        List<Map<String, Object>> errors = outcome.failures().stream()
                .map(failure -> orderError(
                        "id",
                        failure.message(),
                        switch (failure.kind()) {
                            case NOT_FOUND -> "NOT_FOUND";
                            case CONFLICT -> "CANNOT_CANCEL_ORDER";
                        }))
                .toList();
        return bulkPayload(outcome.count(), errors);
    }

    /**
     * Cancel an order by typed global ID. Malformed IDs fail closed. Expected
     * domain failures are returned as the Saleor orderErrors payload instead
     * of being reported as successful mutations.
     */
    @DgsMutation(field = "orderCancel")
    public Map<String, Object> orderCancel(@InputArgument String id) {
        orderCommandSpi.requireAdmin();
        final UUID orderId;
        try {
            orderId = globalIdCodec.decode(id, OrderGraphQlMapper.ORDER_TYPE);
        } catch (IllegalArgumentException exception) {
            return payload(null, List.of(orderError("id", "Invalid global ID", "INVALID")));
        }

        try {
            OrderView cancelled = orderCommandSpi.cancelAsAdmin(orderId);
            return payload(mapper.toGraphQlOrder(cancelled), List.of());
        } catch (ResourceNotFoundException exception) {
            return payload(null, List.of(orderError("id", exception.getMessage(), "NOT_FOUND")));
        } catch (ConflictException exception) {
            return payload(null, List.of(orderError(
                    "id", exception.getMessage(), "CANNOT_CANCEL_ORDER")));
        }
    }

    private static Map<String, Object> orderError(String field, String message, String code) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("field", field);
        error.put("message", message);
        error.put("code", code);
        return error;
    }

    private static Map<String, Object> payload(Order order, List<Map<String, Object>> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        result.put("orderErrors", new ArrayList<>(errors));
        result.put("errors", new ArrayList<>(errors));
        return result;
    }

    private static Map<String, Object> bulkPayload(int count, List<Map<String, Object>> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", count);
        result.put("orderErrors", new ArrayList<>(errors));
        result.put("errors", new ArrayList<>(errors));
        return result;
    }
}
