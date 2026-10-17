package com.marketplace.delivery;

import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/deliveries", version = "1.0")
public class DeliveryController {
    private final DeliveryService service;
    private final DeliveryCourierService courierService;
    private final CurrentUserProvider users;

    public DeliveryController(DeliveryService service, DeliveryCourierService courierService,
                              CurrentUserProvider users) {
        this.service = service;
        this.courierService = courierService;
        this.users = users;
    }

    @PostMapping("/quotes")
    @Operation(summary = "Quote a direct delivery",
            description = "The server obtains a road route and computes the fare; client prices are never accepted.")
    public ResponseEntity<DeliveryQuoteResponse> quote(@Valid @RequestBody CreateDeliveryQuoteRequest request,
                                                       Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createQuote(users.getCurrentUserId(authentication), request));
    }

    @PostMapping("/orders/{orderId}/quotes")
    @Operation(summary = "Quote delivery for a confirmed marketplace order",
            description = "Pickup is resolved from the verified seller's private fulfillment location. The buyer supplies only the dropoff; fare and route are server-derived.")
    public ResponseEntity<DeliveryQuoteResponse> quoteOrder(
            @PathVariable UUID orderId,
            @Valid @RequestBody CreateOrderDeliveryQuoteRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.createOrderQuote(users.getCurrentUserId(authentication), orderId, request));
    }

    @PostMapping("/requests")
    public ResponseEntity<DeliveryRequestResponse> create(@Valid @RequestBody CreateDeliveryRequest request,
                                                         Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createRequest(users.getCurrentUserId(authentication), request.quoteId()));
    }

    @GetMapping("/me")
    public ResponseEntity<PagedResponse<DeliveryRequestResponse>> mine(Pageable pageable,
                                                                       Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(
                service.listMine(users.getCurrentUserId(authentication), pageable)));
    }

    @GetMapping("/requests/{id}")
    public ResponseEntity<DeliveryRequestResponse> get(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(service.get(id, users.getCurrentUserId(authentication), users.isAdmin(authentication)));
    }

    /**
     * The V224 multi-leg operator surface: an order's delivery legs (the
     * checkout-created leg plus any operator splits) and the split command.
     */
    @GetMapping("/admin/orders/{orderId}/legs")
    @org.springframework.web.bind.annotation.ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    public org.springframework.http.ResponseEntity<java.util.List<DeliveryRequestResponse>> listOrderLegs(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID orderId) {
        return org.springframework.http.ResponseEntity.ok(service.listOrderLegs(orderId));
    }

    @PostMapping("/admin/orders/{orderId}/legs")
    @PreAuthorize("hasRole('ADMIN')")
    public org.springframework.http.ResponseEntity<DeliveryRequestResponse> addOrderLeg(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID orderId,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
            SplitLegRequest request) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(service.addLegForOrder(orderId, request.packageDescription(),
                        request.amountMinor()));
    }

    @GetMapping("/admin/requests")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagedResponse<DeliveryRequestResponse>> queue(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(service.listAdmin(pageable)));
    }

    @PutMapping("/courier/availability")
    @PreAuthorize("hasRole('PROVIDER')")
    @Operation(summary = "Set delivery availability and latest courier coordinates",
            description = "Only a verified provider can opt into the automatic courier dispatch pool.")
    public ResponseEntity<DeliveryCourierResponse> updateAvailability(
            @Valid @RequestBody CourierAvailabilityRequest request, Authentication authentication) {
        return ResponseEntity.ok(courierService.updateAvailability(
                users.getCurrentUserId(authentication), request));
    }

    @GetMapping("/courier/me")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<PagedResponse<DeliveryRequestResponse>> courierDeliveries(
            Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(
                service.listForCourier(users.getCurrentUserId(authentication), pageable)));
    }

    @PostMapping("/requests/{id}/cancel")
    @PreAuthorize("hasRole('CONSUMER')")
    @Operation(summary = "Cancel a delivery before courier pickup",
            description = "The delivery lifecycle decides whether to cancel an uncollected payment or refund a collected payment.")
    public ResponseEntity<DeliveryRequestResponse> cancel(
            @PathVariable UUID id, @Valid @RequestBody CancelDeliveryRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(service.cancelForRequester(
                id, users.getCurrentUserId(authentication), request.reason()));
    }

    @PostMapping("/requests/{id}/transition")
    @PreAuthorize("hasRole('PROVIDER')")
    @Operation(summary = "Update delivery status as its assigned courier")
    public ResponseEntity<DeliveryRequestResponse> transition(
            @PathVariable UUID id, @Valid @RequestBody TransitionDeliveryRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(service.transition(id, users.getCurrentUserId(authentication),
                request.targetStatus(), request.reason(), request.proofCode()));
    }

    /** The V224 split command: the package's description and its share of the frozen shipping fee. */
    public record SplitLegRequest(
            @jakarta.validation.constraints.Size(max = 500) String packageDescription,
            @jakarta.validation.constraints.Positive long amountMinor) {
    }
}
