package com.marketplace.orders;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.security.CurrentUserProvider;
import com.marketplace.orders.spi.OrderReturnsPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The GraphQL/REST-neutral adapter to the established RMA state machine.
 * Authorization and ownership are enforced here and again by the owning
 * service; refund money still moves only through OrderRefundPort at completion.
 */
@Component
public class OrderReturnsManagementAdapter implements OrderReturnsPort {

    private static final int MAX_PAGE_SIZE = 50;

    private final OrderReturnsService returns;
    private final CurrentUserProvider currentUserProvider;

    public OrderReturnsManagementAdapter(OrderReturnsService returns,
                                         CurrentUserProvider currentUserProvider) {
        this.returns = returns;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public OrderReturnView requestReturn(ReturnRequest command, Authentication authentication) {
        if (command == null || command.orderId() == null || command.type() == null
                || command.reason() == null || command.reason().isBlank()
                || command.reason().length() > 500 || command.lines().isEmpty()
                || command.lines().size() > 50) {
            throw new BadRequestException("A return requires an order, type, reason and 1..50 lines");
        }
        Map<UUID, Integer> lines = new LinkedHashMap<>();
        for (ReturnLineInput line : command.lines()) {
            if (line == null || line.orderItemId() == null || line.quantity() <= 0) {
                throw new BadRequestException("Every return line requires an order item and positive quantity");
            }
            if (lines.putIfAbsent(line.orderItemId(), line.quantity()) != null) {
                throw new BadRequestException("A return carries one line per order item; merge duplicates");
            }
        }
        OrderReturn requested = returns.request(command.orderId(),
                currentUserProvider.getCurrentUserId(authentication),
                OrderReturn.ReturnType.valueOf(command.type()), command.reason(), lines);
        return toView(requested, returns.linesOf(requested.getId()));
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ReturnPageView listMyReturns(int page, int size, Authentication authentication) {
        Page<OrderReturn> found = returns.listMine(currentUserProvider.getCurrentUserId(authentication),
                PageRequest.of(page, boundedSize(size)));
        return toPage(found);
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public OrderReturnView getMyReturn(UUID returnId, Authentication authentication) {
        OrderReturn found = returns.getForBuyer(returnId, currentUserProvider.getCurrentUserId(authentication));
        return toView(found, returns.linesOf(returnId));
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public OrderReturnView cancelMyReturn(UUID returnId, Authentication authentication) {
        OrderReturn found = returns.cancelForBuyer(returnId, currentUserProvider.getCurrentUserId(authentication));
        return toView(found, returns.linesOf(returnId));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ReturnPageView listAdminReturns(int page, int size, Authentication authentication) {
        Page<OrderReturn> found = returns.listAdmin(PageRequest.of(page, boundedSize(size)));
        return toPage(found);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public OrderReturnView getAdminReturn(UUID returnId, Authentication authentication) {
        OrderReturn found = returns.getForAdmin(returnId);
        return toView(found, returns.linesOf(returnId));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderReturnView approveReturn(UUID returnId, Authentication authentication) {
        OrderReturn found = returns.approve(returnId);
        return toView(found, returns.linesOf(returnId));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderReturnView rejectReturn(UUID returnId, String note, Authentication authentication) {
        OrderReturn found = returns.reject(returnId, note);
        return toView(found, returns.linesOf(returnId));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderReturnView receiveReturn(UUID returnId, Authentication authentication) {
        OrderReturn found = returns.receive(returnId);
        return toView(found, returns.linesOf(returnId));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderReturnView completeReturn(UUID returnId, Authentication authentication) {
        OrderReturn found = returns.complete(returnId);
        return toView(found, returns.linesOf(returnId));
    }

    private ReturnPageView toPage(Page<OrderReturn> page) {
        List<UUID> ids = page.getContent().stream().map(OrderReturn::getId).toList();
        Map<UUID, List<OrderReturnLine>> lines = returns.linesByReturnIds(ids);
        List<OrderReturnView> items = page.getContent().stream()
                .map(r -> toView(r, lines.getOrDefault(r.getId(), List.of())))
                .toList();
        return new ReturnPageView(items, page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.hasNext(), page.hasPrevious());
    }

    private static OrderReturnView toView(OrderReturn r, List<OrderReturnLine> lineEntities) {
        return new OrderReturnView(r.getId(), r.getOrderId(), r.getReturnType().name(),
                r.getStatus().name(), r.getReason(), r.getResolutionNote(), r.getRefundAmountMinor(),
                r.getCurrency(), r.getReplacementOrderId(), r.getRequestedAt(), r.getApprovedAt(),
                r.getReceivedAt(), r.getCompletedAt(), lineEntities.stream()
                        .map(line -> new OrderReturnLineView(line.getId(), line.getOrderItemId(),
                                line.getProductId(), line.getVariantId(), line.getQuantity()))
                        .toList());
    }

    private static int boundedSize(int requested) {
        if (requested <= 0) {
            throw new BadRequestException("Return page size must be positive");
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
