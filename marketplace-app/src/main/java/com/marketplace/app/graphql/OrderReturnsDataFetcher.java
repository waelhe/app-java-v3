package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.OrderReturn;
import com.marketplace.app.graphql.generated.types.OrderReturnLineRequestInput;
import com.marketplace.app.graphql.generated.types.OrderReturnPage;
import com.marketplace.app.graphql.generated.types.OrderReturnRequestInput;
import com.marketplace.orders.spi.OrderReturnsPort;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Thin DGS adapter: input validation/ID coercion only; all RMA transitions remain in marketplace-orders. */
@DgsComponent
public class OrderReturnsDataFetcher {

    private static final int MAX_LINES = 50;
    private static final int PAGE_SIZE_CEILING = 50;

    private final OrderReturnsPort returns;
    private final OrderReturnsGraphQlMapper mapper;
    private final GraphQlProperties properties;

    public OrderReturnsDataFetcher(OrderReturnsPort returns,
                                   OrderReturnsGraphQlMapper mapper,
                                   GraphQlProperties properties) {
        this.returns = returns;
        this.mapper = mapper;
        this.properties = properties;
    }

    @DgsQuery(field = DgsConstants.QUERY.MyOrderReturns)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public OrderReturnPage myOrderReturns(@InputArgument Integer page, @InputArgument Integer first) {
        return mapper.toResponse(returns.listMyReturns(pageNumber(page), pageSize(first), currentAuthentication()));
    }

    @DgsQuery(field = DgsConstants.QUERY.MyOrderReturn)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public OrderReturn myOrderReturn(@InputArgument UUID id) {
        return mapper.toResponse(returns.getMyReturn(requireId(id), currentAuthentication()));
    }

    @DgsQuery(field = DgsConstants.QUERY.AdminOrderReturns)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public OrderReturnPage adminOrderReturns(@InputArgument Integer page, @InputArgument Integer first) {
        return mapper.toResponse(returns.listAdminReturns(pageNumber(page), pageSize(first), currentAuthentication()));
    }

    @DgsQuery(field = DgsConstants.QUERY.AdminOrderReturn)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public OrderReturn adminOrderReturn(@InputArgument UUID id) {
        return mapper.toResponse(returns.getAdminReturn(requireId(id), currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.RequestOrderReturn)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public OrderReturn requestOrderReturn(@InputArgument OrderReturnRequestInput input) {
        return mapper.toResponse(returns.requestReturn(toCommand(input), currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.CancelMyOrderReturn)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public OrderReturn cancelMyOrderReturn(@InputArgument UUID id) {
        return mapper.toResponse(returns.cancelMyReturn(requireId(id), currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.ApproveOrderReturn)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public OrderReturn approveOrderReturn(@InputArgument UUID id) {
        return mapper.toResponse(returns.approveReturn(requireId(id), currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.RejectOrderReturn)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public OrderReturn rejectOrderReturn(@InputArgument UUID id, @InputArgument String note) {
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException("note must be 500 characters or fewer");
        }
        return mapper.toResponse(returns.rejectReturn(requireId(id), note, currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.ReceiveOrderReturn)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public OrderReturn receiveOrderReturn(@InputArgument UUID id) {
        return mapper.toResponse(returns.receiveReturn(requireId(id), currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.CompleteOrderReturn)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public OrderReturn completeOrderReturn(@InputArgument UUID id) {
        return mapper.toResponse(returns.completeReturn(requireId(id), currentAuthentication()));
    }

    private OrderReturnsPort.ReturnRequest toCommand(OrderReturnRequestInput input) {
        if (input == null || input.getOrderId() == null || input.getOrderId().isBlank()
                || input.getType() == null || input.getReason() == null || input.getReason().isBlank()
                || input.getReason().length() > 500 || input.getLines() == null || input.getLines().isEmpty()
                || input.getLines().size() > MAX_LINES) {
            throw new IllegalArgumentException("orderId, type, reason and 1..50 return lines are required");
        }
        UUID orderId = parseId(input.getOrderId(), "orderId");
        List<OrderReturnsPort.ReturnLineInput> lines = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (OrderReturnLineRequestInput line : input.getLines()) {
            // quantity is Int! in the SDL — the generated accessor is a primitive
            // int (the GraphQL layer rejects null before the resolver), so the guard
            // carries the semantic bound alone.
            if (line == null || line.getOrderItemId() == null || line.getQuantity() <= 0) {
                throw new IllegalArgumentException("each line needs an orderItemId and positive quantity");
            }
            UUID itemId = parseId(line.getOrderItemId(), "orderItemId");
            if (!seen.add(itemId)) {
                throw new IllegalArgumentException("a return carries one line per order item; merge duplicates");
            }
            lines.add(new OrderReturnsPort.ReturnLineInput(itemId, line.getQuantity()));
        }
        return new OrderReturnsPort.ReturnRequest(orderId, input.getType().name(), input.getReason(), lines);
    }

    private int pageNumber(Integer requested) {
        if (requested == null) return 0;
        if (requested < 0 || requested > 1_000_000) {
            throw new IllegalArgumentException("page must be between 0 and 1000000");
        }
        return requested;
    }

    private int pageSize(Integer requested) {
        int value = requested == null ? properties.pagination().defaultPageSize() : requested;
        if (value <= 0) throw new IllegalArgumentException("first must be positive");
        return Math.min(value, Math.min(PAGE_SIZE_CEILING, properties.pagination().maxPageSize()));
    }

    private UUID requireId(UUID id) {
        if (id == null) throw new IllegalArgumentException("id is required");
        return id;
    }

    private static UUID parseId(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }

    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required for order returns");
        }
        return authentication;
    }
}
