package com.marketplace.orders.spi;

import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transport-neutral boundary for buyer returns and administrative
 * return/refund operations.
 *
 * <p><b>Placement (the ArchitectureRulesTest verdict, the ProductAdminSpi
 * pattern):</b> the seam carries Spring Security's {@code Authentication},
 * so it lives in the OWNING module's {@code spi} package —
 * {@code shared.api} is the framework-neutral zone by the #429 build gate.
 */
@NamedInterface("order-returns")
public interface OrderReturnsPort {

    OrderReturnView requestReturn(ReturnRequest command, Authentication authentication);
    ReturnPageView listMyReturns(int page, int size, Authentication authentication);
    OrderReturnView getMyReturn(UUID returnId, Authentication authentication);
    OrderReturnView cancelMyReturn(UUID returnId, Authentication authentication);

    ReturnPageView listAdminReturns(int page, int size, Authentication authentication);
    OrderReturnView getAdminReturn(UUID returnId, Authentication authentication);
    OrderReturnView approveReturn(UUID returnId, Authentication authentication);
    OrderReturnView rejectReturn(UUID returnId, String note, Authentication authentication);
    OrderReturnView receiveReturn(UUID returnId, Authentication authentication);
    OrderReturnView completeReturn(UUID returnId, Authentication authentication);

    record ReturnRequest(UUID orderId, String type, String reason, List<ReturnLineInput> lines) {
        public ReturnRequest {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    record ReturnLineInput(UUID orderItemId, int quantity) { }

    record OrderReturnView(
            UUID id,
            UUID orderId,
            String type,
            String status,
            String reason,
            String resolutionNote,
            Long refundAmountMinor,
            String currency,
            UUID replacementOrderId,
            Instant requestedAt,
            Instant approvedAt,
            Instant receivedAt,
            Instant completedAt,
            List<OrderReturnLineView> lines) {
        public OrderReturnView {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    record OrderReturnLineView(
            UUID id,
            UUID orderItemId,
            UUID productId,
            UUID variantId,
            int quantity) { }

    record ReturnPageView(
            List<OrderReturnView> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean hasNext,
            boolean hasPrevious) {
        public ReturnPageView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
