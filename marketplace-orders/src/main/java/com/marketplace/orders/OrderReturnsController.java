package com.marketplace.orders;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The V225 RMA surface: the buyer's request/cancel/read edges and the
 * operator's APPROVE/REJECT/RECEIVE/COMPLETE edges — every command answers
 * the return's current state (the read-back rule), and the buyer's reads
 * stay buyer-scoped (an honest 404 to anyone else).
 */
@RestController
@RequestMapping(value = ApiConstants.ORDERS + "/returns", version = "1.0")
public class OrderReturnsController {

    private final OrderReturnsService returnsService;
    private final CurrentUserProvider currentUserProvider;

    public OrderReturnsController(OrderReturnsService returnsService,
                                  CurrentUserProvider currentUserProvider) {
        this.returnsService = returnsService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    @Operation(summary = "Open a return or exchange request", description = "The buyer's edge: "
            + "a RETURN or EXCHANGE against their FULFILLED order's lines. The lines must belong "
            + "to the order, the quantities must fit the frozen lines beside any open returns, "
            + "and the reason is required. The answer IS the opened request.")
    public ResponseEntity<OrderReturnResponse> request(
            @Valid @RequestBody ReturnRequestRequest body, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        Map<UUID, Integer> lines = new LinkedHashMap<>();
        body.lines().forEach(l -> lines.put(l.orderItemId(), l.quantity()));
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(OrderReturnResponse.of(
                        returnsService.request(body.orderId(), caller, body.type(),
                                body.reason(), lines),
                        List.of()));
    }

    @GetMapping("/me")
    @Operation(summary = "List the caller's returns", description = "The caller's own return "
            + "history, newest-first, paginated.")
    public ResponseEntity<com.marketplace.shared.api.PagedResponse<OrderReturnResponse>> listMine(
            Pageable pageable, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(com.marketplace.shared.api.PagedResponse.of(
                returnsService.listMine(caller, pageable).map(r ->
                        OrderReturnResponse.of(r, List.of()))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read one return", description = "The buyer's own return with its "
            + "lines (an honest 404 to anyone else).")
    public ResponseEntity<OrderReturnResponse> getForBuyer(
            @PathVariable UUID id, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(OrderReturnResponse.of(
                returnsService.getForBuyer(id, caller), returnsService.linesOf(id)));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel the caller's request", description = "Legal while REQUESTED — "
            + "the buyer's escape hatch before the operator decides.")
    public ResponseEntity<OrderReturnResponse> cancel(
            @PathVariable UUID id, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(OrderReturnResponse.of(
                returnsService.cancelForBuyer(id, caller), returnsService.linesOf(id)));
    }

    // ---------------- the operator's edges ----------------

    @PostMapping("/admin/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Approve a return (ADMIN)", description = "Computes and FREEZES the "
            + "refund share from the order's own frozen breakdown — the lines' share of the "
            + "discounted goods value (plus the exclusive-tax share). The completion executes "
            + "exactly this frozen number.")
    public ResponseEntity<OrderReturnResponse> approve(@PathVariable UUID id) {
        return ResponseEntity.ok(OrderReturnResponse.of(
                returnsService.approve(id), returnsService.linesOf(id)));
    }

    @PostMapping("/admin/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reject a return (ADMIN)", description = "Terminal — with the "
            + "resolution note.")
    public ResponseEntity<OrderReturnResponse> reject(
            @PathVariable UUID id, @Valid @RequestBody ResolutionRequest body) {
        return ResponseEntity.ok(OrderReturnResponse.of(
                returnsService.reject(id, body.note()), returnsService.linesOf(id)));
    }

    @PostMapping("/admin/{id}/receive")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Mark the goods received (ADMIN)", description = "The goods arrive — "
            + "the shelves restock through the catalog's restock port (the physical truth).")
    public ResponseEntity<OrderReturnResponse> receive(@PathVariable UUID id) {
        return ResponseEntity.ok(OrderReturnResponse.of(
                returnsService.receive(id), returnsService.linesOf(id)));
    }

    @PostMapping("/admin/{id}/complete")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Complete a return or exchange (ADMIN)", description = "A RETURN "
            + "executes the FROZEN partial refund through the ONE refund contract; an EXCHANGE "
            + "creates the replacement order (same seller and lines, zero payment) and links it.")
    public ResponseEntity<OrderReturnResponse> complete(@PathVariable UUID id) {
        return ResponseEntity.ok(OrderReturnResponse.of(
                returnsService.complete(id), returnsService.linesOf(id)));
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List every return (ADMIN)", description = "The operator's queue, "
            + "newest-first, paginated.")
    public ResponseEntity<com.marketplace.shared.api.PagedResponse<OrderReturnResponse>> listAdmin(
            Pageable pageable) {
        return ResponseEntity.ok(com.marketplace.shared.api.PagedResponse.of(
                returnsService.listAdmin(pageable).map(r ->
                        OrderReturnResponse.of(r, List.of()))));
    }

    // ---------------- records ----------------

    public record ReturnLineRequest(
            @NotNull UUID orderItemId,
            @Positive int quantity) {
    }

    public record ReturnRequestRequest(
            @NotNull UUID orderId,
            @NotNull OrderReturn.ReturnType type,
            @NotBlank @Size(max = 500) String reason,
            // @Valid cascades INTO the elements (Jakarta Bean Validation does
            // not validate container elements without it — the review's
            // finding): a line with quantity 0 or a null orderItemId answers
            // the honest 400 at the boundary instead of the service's
            // IllegalArgumentException surfacing as a 500 after the return
            // row was already saved.
            @NotNull @Size(min = 1, max = 50) List<@Valid ReturnLineRequest> lines) {

        public List<ReturnLineRequest> lines() {
            var seen = new LinkedHashMap<UUID, ReturnLineRequest>();
            for (ReturnLineRequest line : lines) {
                seen.merge(line.orderItemId(), line, (a, b) ->
                        new ReturnLineRequest(a.orderItemId(), a.quantity() + b.quantity()));
            }
            if (seen.size() != lines.size()) {
                throw new BadRequestException(
                        "A return carries one line per order item — merge the duplicate lines");
            }
            return List.copyOf(seen.values());
        }
    }

    public record ResolutionRequest(
            @Size(max = 500) String note) {
    }

    public record OrderReturnLineResponse(
            UUID id, UUID orderItemId, UUID productId, UUID variantId, int quantity) {

        static OrderReturnLineResponse of(OrderReturnLine line) {
            return new OrderReturnLineResponse(line.getId(), line.getOrderItemId(),
                    line.getProductId(), line.getVariantId(), line.getQuantity());
        }
    }

    public record OrderReturnResponse(
            UUID id, UUID orderId, OrderReturn.ReturnType type, OrderReturn.ReturnStatus status,
            String reason, String resolutionNote, Long refundAmountMinor, String currency,
            UUID replacementOrderId,
            java.time.Instant requestedAt, java.time.Instant approvedAt,
            java.time.Instant receivedAt, java.time.Instant completedAt,
            List<OrderReturnLineResponse> lines) {

        static OrderReturnResponse of(OrderReturn r, List<OrderReturnLine> lineEntities) {
            return new OrderReturnResponse(r.getId(), r.getOrderId(), r.getReturnType(),
                    r.getStatus(), r.getReason(), r.getResolutionNote(), r.getRefundAmountMinor(),
                    r.getCurrency(), r.getReplacementOrderId(), r.getRequestedAt(),
                    r.getApprovedAt(), r.getReceivedAt(), r.getCompletedAt(),
                    lineEntities.stream().map(OrderReturnLineResponse::of).toList());
        }
    }
}
