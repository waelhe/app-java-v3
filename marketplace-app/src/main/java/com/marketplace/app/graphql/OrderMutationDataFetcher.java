package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Order;
import com.marketplace.orders.OrdersService;
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

    private final OrdersService ordersService;
    private final OrderGraphQlMapper mapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public OrderMutationDataFetcher(OrdersService ordersService,
                                    OrderGraphQlMapper mapper,
                                    SaleorGlobalIdCodec globalIdCodec) {
        this.ordersService = ordersService;
        this.mapper = mapper;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Cancel an order by typed global ID. Malformed IDs fail closed. Expected
     * domain failures are returned as the Saleor orderErrors payload instead
     * of being reported as successful mutations.
     */
    @DgsMutation(field = "orderCancel")
    public Map<String, Object> orderCancel(@InputArgument String id) {
        final UUID orderId;
        try {
            orderId = globalIdCodec.decode(id, OrderGraphQlMapper.ORDER_TYPE);
        } catch (IllegalArgumentException exception) {
            return payload(null, List.of(orderError("id", "Invalid global ID", "INVALID")));
        }

        try {
            com.marketplace.orders.Order cancelled = ordersService.cancelAsAdmin(orderId);
            OrderView view = new OrderView(
                    cancelled.getId(), cancelled.getPlacedAt(), cancelled.getStatus().name());
            return payload(mapper.toGraphQlOrder(view), List.of());
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
        return result;
    }
}
