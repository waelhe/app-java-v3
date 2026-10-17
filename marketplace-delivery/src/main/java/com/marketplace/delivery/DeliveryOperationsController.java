package com.marketplace.delivery;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The V226 delivery OPERATIONS surface (the architecture §4.2's delivery
 * and shipping workbench — the four measured pieces): the dispatch
 * board's queues, the courier roster and lifecycle, the carrier
 * registry, and the settlement reconciliation. Every command answers its
 * read-back; the source state machines stay in their domain owners.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1 + "/deliveries/admin", version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class DeliveryOperationsController {

    private final DispatchQueueService dispatchQueues;
    private final CourierOperationsService couriers;
    private final CarrierRegistry carriers;
    private final DeliveryReconciliationService reconciliation;

    public DeliveryOperationsController(DispatchQueueService dispatchQueues,
                                        CourierOperationsService couriers,
                                        CarrierRegistry carriers,
                                        DeliveryReconciliationService reconciliation) {
        this.dispatchQueues = dispatchQueues;
        this.couriers = couriers;
        this.carriers = carriers;
        this.reconciliation = reconciliation;
    }

    // ---------------- the dispatch board ----------------

    @GetMapping("/dispatch/queues")
    @Operation(summary = "The dispatch board's snapshot", description = "Per-state counts of "
            + "the active legs (awaiting payment through in transit) plus the at-risk promise "
            + "count — the non-terminal legs whose promised window already passed.")
    public ResponseEntity<DispatchQueueService.DispatchQueuesSnapshot> queues() {
        return ResponseEntity.ok(dispatchQueues.snapshot());
    }

    @GetMapping("/dispatch/queue")
    @Operation(summary = "One state's queue page", description = "The dispatcher's work list "
            + "for one state, oldest first.")
    public ResponseEntity<com.marketplace.shared.api.PagedResponse<DeliveryRequestResponse>> queue(
            @RequestParam DeliveryRequestStatus state, Pageable pageable) {
        return ResponseEntity.ok(com.marketplace.shared.api.PagedResponse.of(
                dispatchQueues.queue(state, pageable)));
    }

    // ---------------- the courier roster ----------------

    @GetMapping("/couriers")
    @Operation(summary = "The couriers roster", description = "The couriers board: status, "
            + "availability, live location freshness, and the current job.")
    public ResponseEntity<com.marketplace.shared.api.PagedResponse<CourierOperationsService.CourierStatusResponse>> roster(
            Pageable pageable) {
        return ResponseEntity.ok(com.marketplace.shared.api.PagedResponse.of(
                couriers.roster(pageable)));
    }

    @PostMapping("/couriers/{userId}/suspend")
    @Operation(summary = "Suspend a courier", description = "The operator's standing verdict: "
            + "the courier disappears from dispatch eligibility instantly; an in-flight leg "
            + "finishes untouched (tearing it away would orphan a physical delivery mid-route).")
    public ResponseEntity<CourierOperationsService.CourierStatusResponse> suspend(
            @PathVariable java.util.UUID userId, @Valid @RequestBody SuspendRequest request) {
        return ResponseEntity.ok(couriers.suspend(userId, request.reason()));
    }

    @PostMapping("/couriers/{userId}/reactivate")
    @Operation(summary = "Reactivate a courier", description = "The operator's reversal — the "
            + "courier re-declares availability to re-enter dispatch.")
    public ResponseEntity<CourierOperationsService.CourierStatusResponse> reactivate(
            @PathVariable java.util.UUID userId) {
        return ResponseEntity.ok(couriers.reactivate(userId));
    }

    // ---------------- the carrier registry ----------------

    @GetMapping("/carriers")
    @Operation(summary = "The configured carriers", description = "Every carrier channel with "
            + "its stable code and declared capabilities — the platform fleet today, external "
            + "partners behind their own adapters as their approvals arrive.")
    public ResponseEntity<List<CarrierResponse>> carriers() {
        return ResponseEntity.ok(carriers.all().stream()
                .map(c -> new CarrierResponse(c.code(), c.capabilities().stream()
                        .map(Enum::name).sorted().toList()))
                .toList());
    }

    // ---------------- the settlement reconciliation ----------------

    @GetMapping("/reconciliation")
    @Operation(summary = "The settlement reconciliation window", description = "Per currency: "
            + "the quoted charges, the collected amounts (quote-borne legs whose intent "
            + "completed), the courier payables, and the order-borne quoted totals (the "
            + "checkout legs whose fees rode their orders' intents — kept apart, never "
            + "double-counted). The financial books remain the ledger's balanced journal.")
    public ResponseEntity<DeliveryReconciliationService.ReconciliationReport> reconciliation(
            @RequestParam Instant from, @RequestParam Instant to) {
        return ResponseEntity.ok(reconciliation.reconcile(from, to));
    }

    // ---------------- records ----------------

    public record SuspendRequest(
            @NotBlank @Size(max = 500) String reason) {
    }

    public record CarrierResponse(String code, List<String> capabilities) {
    }
}
