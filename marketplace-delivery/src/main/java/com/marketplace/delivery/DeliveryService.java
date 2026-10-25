package com.marketplace.delivery;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.DeliveryCompletedEvent;
import com.marketplace.shared.api.DeliveryPaymentPort;
import com.marketplace.shared.api.DeliveryRefundRequestedEvent;
import com.marketplace.shared.api.OrderDeliveryDetails;
import com.marketplace.shared.api.OrderDeliveryPort;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.ProviderFulfillmentLocation;
import com.marketplace.shared.api.ProviderFulfillmentPort;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class DeliveryService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final DeliveryProperties properties;
    private final DeliveryQuoteRepository quotes;
    private final DeliveryRequestRepository requests;
    private final DeliveryRequestEventRepository events;
    private final DeliveryPaymentPort payments;
    private final PaymentIntentLookupPort paymentIntents;
    private final ApplicationEventPublisher publisher;
    private final DeliveryRouteProvider routes;
    private final DeliveryCourierRepository couriers;
    private final ProviderLookupPort providers;
    private final ProviderFulfillmentPort fulfillmentLocations;
    private final OrderDeliveryPort orders;

    public DeliveryService(DeliveryProperties properties, DeliveryQuoteRepository quotes,
            DeliveryRequestRepository requests, DeliveryRequestEventRepository events,
            DeliveryPaymentPort payments, PaymentIntentLookupPort paymentIntents,
            ApplicationEventPublisher publisher, DeliveryRouteProvider routes,
            DeliveryCourierRepository couriers, ProviderLookupPort providers,
            ProviderFulfillmentPort fulfillmentLocations, OrderDeliveryPort orders) {
        this.properties = properties; this.quotes = quotes; this.requests = requests;
        this.events = events; this.payments = payments; this.paymentIntents = paymentIntents;
        this.publisher = publisher; this.routes = routes; this.couriers = couriers; this.providers = providers;
        this.fulfillmentLocations = fulfillmentLocations; this.orders = orders;
    }

    @Observed(name = "delivery.quote.create")
    public DeliveryQuoteResponse createQuote(UUID requesterId, CreateDeliveryQuoteRequest input) {
        return createQuoteFromStops(requesterId, input.pickupAddress(), input.pickupLatitude(),
                input.pickupLongitude(), input.dropoffAddress(), input.dropoffLatitude(),
                input.dropoffLongitude(), input.packageDescription(), null, properties.currency());
    }

    @Observed(name = "delivery.order.quote.create")
    public DeliveryQuoteResponse createOrderQuote(UUID buyerId, UUID orderId,
                                                   CreateOrderDeliveryQuoteRequest input) {
        OrderDeliveryDetails order = orders.requireConfirmedOrderForDelivery(orderId, buyerId);
        if (order.activeDeliveryRequestId() != null) {
            throw new ConflictException("This order already has a delivery request");
        }
        // The V224 guard: an order whose fulfillment was selected at
        // checkout never buys a second delivery — its fee is already inside
        // the order's agreement and its legs are created by the confirmation
        // listener. The manual add-on path serves the legacy (method-less)
        // orders only.
        com.marketplace.shared.api.OrderFulfillmentSelection selection =
                orders.requireOrderFulfillment(orderId, buyerId);
        if (selection.platformDelivery()) {
            throw new ConflictException(
                    "This order's delivery was selected at checkout — its legs are created by confirmation");
        }
        if (order.currency() == null || properties.currency() == null
                || !order.currency().equalsIgnoreCase(properties.currency().trim())) {
            throw new ConflictException("Delivery fare is not configured for order currency " + order.currency());
        }
        ProviderFulfillmentLocation pickup = fulfillmentLocations.findVerifiedLocation(order.sellerId())
                .orElseThrow(() -> new ConflictException(
                        "The verified seller must configure a private pickup location before delivery can be quoted"));
        return createQuoteFromStops(buyerId, pickup.address(), pickup.latitude(), pickup.longitude(),
                input.dropoffAddress(), input.dropoffLatitude(), input.dropoffLongitude(),
                input.packageDescription(), order.orderId(), order.currency());
    }

    private DeliveryQuoteResponse createQuoteFromStops(UUID requesterId,
                                                        String pickupAddress, double pickupLatitude,
                                                        double pickupLongitude, String dropoffAddress,
                                                        double dropoffLatitude, double dropoffLongitude,
                                                        String packageDescription, UUID orderId, String currency) {
        ensureConfigured();
        DeliveryRouteMetrics route = routes.route(pickupLatitude, pickupLongitude,
                dropoffLatitude, dropoffLongitude);
        if (route.distanceMeters() > properties.maxDistanceMeters()) {
            throw new ConflictException("Route is outside the configured delivery area");
        }
        long km = Math.max(1, (route.distanceMeters() + 999L) / 1000L);
        long amount = multiplyAndAdd(properties.baseFeeMinor(), properties.perKilometerMinor(), km,
                "Customer delivery fare overflow");
        long compensation = multiplyAndAdd(properties.courierBaseCompensationMinor(),
                properties.courierCompensationPerKilometerMinor(), km, "Courier compensation overflow");
        if (compensation <= 0 || compensation >= amount) {
            throw new ServiceUnavailableException(
                    "Delivery fare must cover positive courier compensation and platform margin");
        }
        Instant now = Instant.now();
        DeliveryQuote quote = new DeliveryQuote(UUID.randomUUID(), requesterId,
                pickupAddress.trim(), pickupLatitude, pickupLongitude,
                dropoffAddress.trim(), dropoffLatitude, dropoffLongitude,
                packageDescription.trim(), route.distanceMeters(), route.durationSeconds(),
                amount, compensation, currency.trim().toUpperCase(Locale.ROOT),
                now.plusSeconds(properties.quoteTtlSeconds()), orderId);
        return DeliveryQuoteResponse.from(quotes.save(quote));
    }

    @Observed(name = "delivery.request.create")
    public DeliveryRequestResponse createRequest(UUID requesterId, UUID quoteId) {
        DeliveryRequest existing = requests.findByQuoteId(quoteId).orElse(null);
        if (existing != null) {
            if (!existing.getRequesterId().equals(requesterId)) throw new ResourceNotFoundException("Quote not found");
            return DeliveryRequestResponse.from(existing, null);
        }
        Instant now = Instant.now();
        DeliveryQuote quote = quotes.findByIdForUpdate(quoteId)
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found"));
        if (!quote.getRequesterId().equals(requesterId)) throw new ResourceNotFoundException("Quote not found");
        if (quote.getConsumedAt() != null || !quote.getExpiresAt().isAfter(now)) {
            DeliveryRequest raced = requests.findByQuoteId(quoteId).orElse(null);
            if (raced != null && raced.getRequesterId().equals(requesterId)) {
                return DeliveryRequestResponse.from(raced, null);
            }
            throw new ConflictException("Quote expired or consumed");
        }
        String proofCode = proofCode();
        DeliveryRequest request = requests.save(new DeliveryRequest(UUID.randomUUID(), quote, sha256(proofCode)));
        if (quote.getOrderId() != null) {
            orders.attachDeliveryRequest(quote.getOrderId(), requesterId, request.getId());
        }
        quote.consume(now);
        quotes.save(quote);
        record(request, requesterId, null, request.getStatus(), "Request created");
        PaymentIntentDetails payment = payments.createForDelivery(
                request.getId(), requesterId, request.getAmountMinor(), request.getCurrency());
        request.attachPaymentIntent(payment.paymentIntentId());
        requests.save(request);
        return DeliveryRequestResponse.from(request, proofCode);
    }

    @Transactional(readOnly = true)
    public Page<DeliveryRequestResponse> listMine(UUID requesterId, Pageable pageable) {
        return requests.findByRequesterIdOrderByCreatedAtDesc(requesterId, pageable)
                .map(r -> DeliveryRequestResponse.from(r, null));
    }

    @Transactional(readOnly = true)
    public Page<DeliveryRequestResponse> listForCourier(UUID courierId, Pageable pageable) {
        providers.findByUserId(courierId)
                .filter(provider -> "VERIFIED".equals(provider.status()))
                .orElseThrow(() -> new AccessDeniedException("Verified courier provider is required"));
        return requests.findByCourierUserIdOrderByCreatedAtDesc(courierId, pageable)
                .map(r -> DeliveryRequestResponse.from(r, null));
    }

    @Transactional(readOnly = true)
    public Page<DeliveryRequestResponse> listAdmin(Pageable pageable) {
        return requests.findAllByOrderByCreatedAtDesc(pageable)
                .map(r -> DeliveryRequestResponse.from(r, null));
    }

    @Transactional(readOnly = true)
    public DeliveryRequestResponse get(UUID id, UUID userId, boolean admin) {
        DeliveryRequest r = require(id);
        boolean requester = r.getRequesterId().equals(userId);
        boolean assignedCourier = userId.equals(r.getCourierUserId());
        if (!admin && !requester && !assignedCourier) {
            throw new ResourceNotFoundException("Delivery request not found");
        }
        if (assignedCourier && !admin) {
            providers.findByUserId(userId).filter(p -> "VERIFIED".equals(p.status()))
                    .orElseThrow(() -> new ResourceNotFoundException("Delivery request not found"));
        }
        return DeliveryRequestResponse.from(r, null);
    }

    @Observed(name = "delivery.request.cancel")
    public DeliveryRequestResponse cancelForRequester(UUID id, UUID requesterId, String reason) {
        DeliveryRequest request = requests.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery request not found"));
        if (!request.getRequesterId().equals(requesterId)) {
            throw new ResourceNotFoundException("Delivery request not found");
        }
        if (request.getStatus() == DeliveryRequestStatus.CANCELLED) {
            return DeliveryRequestResponse.from(request, null);
        }
        DeliveryRequestStatus before = request.getStatus();
        Instant now = Instant.now();
        try {
            request.cancel(reason);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw new ConflictException(ex.getMessage());
        }
        UUID courierId = request.getCourierUserId();
        if (courierId != null) {
            DeliveryCourier courier = couriers.findByUserIdForUpdate(courierId)
                    .orElseThrow(() -> new IllegalStateException("Assigned courier record is missing"));
            courier.finish(id, true, now, properties.courierLocationMaxAgeSeconds());
            couriers.save(courier);
        }
        requests.save(request);
        record(request, requesterId, before, request.getStatus(), reason.trim());
        if (request.getPaymentIntentId() != null) {
            publisher.publishEvent(new DeliveryRefundRequestedEvent(
                    request.getId(), reason.trim(), request.getOrderId()));
        } else if (request.getOrderId() != null) {
            orders.clearDeliveryRequest(request.getOrderId(), request.getId());
        }
        return DeliveryRequestResponse.from(request, null);
    }

    @Observed(name = "delivery.job.transition")
    public DeliveryRequestResponse transition(UUID id, UUID courierId, DeliveryRequestStatus target,
                                               String reason, String proof) {
        DeliveryRequest r = requests.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery request not found"));
        if (r.getCourierUserId() == null || !r.getCourierUserId().equals(courierId)) {
            throw new AccessDeniedException("Only the assigned courier can update this delivery");
        }
        providers.findByUserId(courierId).filter(p -> "VERIFIED".equals(p.status()))
                .orElseThrow(() -> new AccessDeniedException("Verified courier provider is required"));
        Instant now = Instant.now();
        DeliveryRequestStatus before = r.getStatus();
        if (target == DeliveryRequestStatus.DELIVERED
                && (proof == null || !r.matchesProofHash(sha256(proof)))) {
            throw new AccessDeniedException("Valid delivery proof required");
        }
        if (target != DeliveryRequestStatus.PICKED_UP && target != DeliveryRequestStatus.IN_TRANSIT
                && target != DeliveryRequestStatus.DELIVERED && target != DeliveryRequestStatus.FAILED) {
            throw new ConflictException("Unsupported delivery transition");
        }
        try {
            r.transition(target, reason, now);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw new ConflictException(ex.getMessage());
        }
        if (target == DeliveryRequestStatus.DELIVERED || target == DeliveryRequestStatus.FAILED) {
            DeliveryCourier courier = couriers.findByUserIdForUpdate(courierId)
                    .orElseThrow(() -> new IllegalStateException("Assigned courier record is missing"));
            courier.finish(id, true, now, properties.courierLocationMaxAgeSeconds());
            couriers.save(courier);
        }
        requests.save(r);
        record(r, courierId, before, target, reason);
        if (target == DeliveryRequestStatus.DELIVERED) {
            publisher.publishEvent(new DeliveryCompletedEvent(r.getId(), r.getPaymentIntentId(),
                    r.getAmountMinor(), r.getCurrency(), r.getCourierCompensationMinor(), now,
                    r.getCourierUserId(), r.getOrderId()));
        } else if (target == DeliveryRequestStatus.FAILED && r.getPaymentIntentId() != null) {
            publisher.publishEvent(new DeliveryRefundRequestedEvent(r.getId(), reason, r.getOrderId()));
        }
        return DeliveryRequestResponse.from(r, null);
    }

    @Observed(name = "delivery.payment.settle")
    public void onPaymentStateChanged(PaymentStateChangedEvent event) {
        if (!Set.of("COMPLETED", "FAILED", "CANCELLED", "REFUNDED", "PARTIALLY_REFUNDED")
                .contains(event.state())) return;
        PaymentIntentDetails p = paymentIntents.findById(event.paymentIntentId())
                .orElseThrow(() -> new IllegalStateException("Payment event references an unknown intent"));
        if (!p.isDeliveryOrigin()) return;
        if (p.deliveryRequestId() == null) throw new IllegalStateException("Delivery intent lacks request identity");
        DeliveryRequest r = requests.findByIdForUpdate(p.deliveryRequestId())
                .orElseThrow(() -> new IllegalStateException("Payment references an unknown delivery request"));
        if (!r.getRequesterId().equals(p.consumerId()) || r.getAmountMinor() != p.amountCents()
                || !r.getCurrency().equals(p.currency())
                || !p.paymentIntentId().equals(r.getPaymentIntentId())) {
            throw new IllegalStateException("Payment does not match the authoritative delivery payable");
        }
        DeliveryRequestStatus before = r.getStatus();
        if ("COMPLETED".equals(event.state())) {
            if (before == DeliveryRequestStatus.AWAITING_PAYMENT) {
                if (!"SUCCEEDED".equals(p.status())) {
                    throw new IllegalStateException("COMPLETED event lacks a SUCCEEDED payment intent");
                }
                r.paymentSucceeded();
                requests.save(r);
                record(r, null, before, r.getStatus(), "Payment verified");
            } else if (before == DeliveryRequestStatus.FAILED || before == DeliveryRequestStatus.CANCELLED
                    || before == DeliveryRequestStatus.PAYMENT_FAILED) {
                publisher.publishEvent(new DeliveryRefundRequestedEvent(r.getId(),
                        "Late payment after terminal payment/delivery state", r.getOrderId()));
            }
        } else if (("FAILED".equals(event.state()) || "CANCELLED".equals(event.state()))
                && before == DeliveryRequestStatus.AWAITING_PAYMENT) {
            r.paymentFailed();
            requests.save(r);
            if (r.getOrderId() != null) {
                orders.clearDeliveryRequest(r.getOrderId(), r.getId());
            }
            record(r, null, before, r.getStatus(), "Payment " + event.state());
        } else if (("CANCELLED".equals(event.state()) || "FAILED".equals(event.state())
                || "REFUNDED".equals(event.state()))
                && (before == DeliveryRequestStatus.CANCELLED
                    || before == DeliveryRequestStatus.FAILED
                    || before == DeliveryRequestStatus.PAYMENT_FAILED)) {
            if (r.getOrderId() != null) {
                orders.clearDeliveryRequest(r.getOrderId(), r.getId());
            }
        }
    }

    private DeliveryRequest require(UUID id) {
        return requests.findById(id).orElseThrow(() -> new ResourceNotFoundException("Delivery request not found"));
    }

    /**
     * The V224 confirmation edge (the architecture §3.2 Mode B step 4): the
     * checkout-selected platform delivery becomes its operational leg(s)
     * AFTER the order confirms — created from the FROZEN selection, never
     * re-quoted for the buyer (the fee froze at placement and already rode
     * the order's ONE payment intent). Idempotent by the existing-active
     * leg check: a redelivered {@code OrderConfirmedEvent} finds the leg it
     * already created and returns it — the Modulith events contract's
     * recoverable, idempotent consumer.
     */
    @Observed(name = "delivery.order.leg.create")
    public DeliveryRequestResponse createOrderLegFromFulfillment(
            com.marketplace.shared.api.OrderFulfillmentSelection selection) {
        DeliveryRequest existing = requests.findByOrderIdOrderByCreatedAtAscIdAsc(
                selection.orderId()).stream()
                .filter(r -> r.getStatus() != DeliveryRequestStatus.CANCELLED
                        && r.getStatus() != DeliveryRequestStatus.FAILED
                        && r.getStatus() != DeliveryRequestStatus.RETURNED)
                .findFirst().orElse(null);
        if (existing != null) {
            return DeliveryRequestResponse.from(existing, null);
        }
        ensureConfigured();
        ProviderFulfillmentLocation pickup = fulfillmentLocations
                .findVerifiedLocation(selection.sellerId())
                .orElseThrow(() -> new ConflictException(
                        "The verified seller must configure a private pickup location before delivery can run"));
        DeliveryRouteMetrics route = routes.route(pickup.latitude(), pickup.longitude(),
                selection.shipToLatitude(), selection.shipToLongitude());
        if (route.distanceMeters() > properties.maxDistanceMeters()) {
            throw new ConflictException(
                    "The frozen destination is outside the configured delivery area");
        }
        long km = Math.max(1, (route.distanceMeters() + 999L) / 1000L);
        long compensation = multiplyAndAdd(properties.courierBaseCompensationMinor(),
                properties.courierCompensationPerKilometerMinor(), km,
                "Courier compensation overflow");
        long amount = selection.shippingAmountMinor();
        if (compensation <= 0 || compensation >= amount) {
            throw new ServiceUnavailableException(
                    "Delivery fare must cover positive courier compensation and platform margin");
        }
        String proofCode = proofCode();
        DeliveryRequest leg = requests.save(DeliveryRequest.fromCheckout(
                UUID.randomUUID(), selection.orderId(), selection.buyerId(),
                amount, compensation, selection.currency(),
                route.distanceMeters(), route.durationSeconds(),
                pickup.address(), pickup.latitude(), pickup.longitude(),
                selection.shipToAddress(), selection.shipToLatitude(),
                selection.shipToLongitude(),
                "Order " + selection.orderId() + " checkout delivery",
                sha256(proofCode)));
        // the FIRST leg is the order's primary link (the V218 column); the
        // multi-leg additions ride beside it (delivery_requests.order_id is
        // deliberately not unique — one order, several packages)
        orders.attachDeliveryRequest(selection.orderId(), selection.buyerId(), leg.getId());
        record(leg, null, null, leg.getStatus(), "Leg created from the checkout selection");
        return DeliveryRequestResponse.from(leg, proofCode);
    }

    /**
     * The V224 multi-leg operator command (ADMIN): splits a confirmed
     * order's platform delivery into an ADDITIONAL package — the operator
     * states the package's description and its share of the FROZEN
     * shipping fee. The frozen-fee ceiling holds BY CONSTRUCTION: the
     * split CARVES the new leg's share out of the primary leg (the legs'
     * shipping sum stays exactly what the buyer paid at placement), the
     * primary keeps at least its own courier compensation, and the new
     * leg's share must cover its own compensation — both edges atomic in
     * one transaction.
     */
    @Transactional
    @Observed(name = "delivery.order.leg.split")
    public DeliveryRequestResponse addLegForOrder(UUID orderId, String packageDescription,
                                                  long amountMinor) {
        java.util.List<DeliveryRequest> legs =
                requests.findByOrderIdOrderByCreatedAtAscIdAsc(orderId);
        if (legs.isEmpty()) {
            throw new ConflictException("Order " + orderId + " has no delivery legs to split");
        }
        DeliveryRequest first = legs.get(0);
        com.marketplace.shared.api.OrderFulfillmentSelection selection =
                orders.requireOrderFulfillment(orderId, first.getRequesterId());
        if (!selection.platformDelivery()) {
            // the #604 legacy shape (no checkout selection): its legs are
            // quote-backed and paid on their own — an unpaid add-on leg
            // would go out for dispatch with no payment behind it
            throw new ConflictException(
                    "Only a checkout-selected platform delivery can be split");
        }
        // the honest split ceiling, BY CONSTRUCTION: the split carves the
        // new leg's share out of the primary leg — the legs' shipping sum
        // stays the buyer's frozen agreement (never a minor unit above
        // it), and the primary keeps at least its own courier
        // compensation (the platform never pays margin out of pocket)
        if (amountMinor <= 0
                || amountMinor >= first.getAmountMinor() - first.getCourierCompensationMinor()) {
            throw new ConflictException("A split leg requires a positive share the primary "
                    + "leg's margin can carve — the buyer's frozen fee is the ceiling");
        }
        ensureConfigured();
        ProviderFulfillmentLocation pickup = fulfillmentLocations
                .findVerifiedLocation(selection.sellerId())
                .orElseThrow(() -> new ConflictException(
                        "The verified seller must configure a private pickup location"));
        DeliveryRouteMetrics route = routes.route(pickup.latitude(), pickup.longitude(),
                first.getDropoffLatitude(), first.getDropoffLongitude());
        if (route.distanceMeters() > properties.maxDistanceMeters()) {
            throw new ConflictException("The frozen destination is outside the configured delivery area");
        }
        long km = Math.max(1, (route.distanceMeters() + 999L) / 1000L);
        long compensation = multiplyAndAdd(properties.courierBaseCompensationMinor(),
                properties.courierCompensationPerKilometerMinor(), km,
                "Courier compensation overflow");
        if (compensation <= 0 || compensation >= amountMinor) {
            throw new ConflictException(
                    "A split leg's share must cover positive courier compensation");
        }
        // the redistribution itself: the primary's share gives up what
        // the new leg takes (one atomic transaction with the leg's insert)
        first.carveShare(amountMinor);
        String proofCode = proofCode();
        DeliveryRequest leg = requests.save(DeliveryRequest.fromCheckout(
                UUID.randomUUID(), orderId, first.getRequesterId(),
                amountMinor, compensation, first.getCurrency(),
                route.distanceMeters(), route.durationSeconds(),
                pickup.address(), pickup.latitude(), pickup.longitude(),
                first.getDropoffAddress(), first.getDropoffLatitude(),
                first.getDropoffLongitude(),
                packageDescription == null || packageDescription.isBlank()
                        ? "Order " + orderId + " split package" : packageDescription.strip(),
                sha256(proofCode)));
        record(leg, null, null, leg.getStatus(), "Split leg added by the operator");
        return DeliveryRequestResponse.from(leg, proofCode);
    }

    /** The order's legs — the operator's multi-leg read. */
    @Transactional(readOnly = true)
    public java.util.List<DeliveryRequestResponse> listOrderLegs(UUID orderId) {
        return requests.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream()
                .map(r -> DeliveryRequestResponse.from(r, null)).toList();
    }

    private void record(DeliveryRequest r, UUID actor, DeliveryRequestStatus from, DeliveryRequestStatus to,
                        String reason) {
        events.save(new DeliveryRequestEvent(UUID.randomUUID(), r.getId(), actor,
                from == null ? null : from.name(), to.name(), reason, Instant.now()));
    }

    private void ensureConfigured() {
        if (!properties.enabled() || properties.baseFeeMinor() <= 0 || properties.perKilometerMinor() < 0
                || properties.maxDistanceMeters() <= 0 || properties.quoteTtlSeconds() <= 0
                || properties.currency() == null || !properties.currency().trim().matches("[A-Za-z]{3}")
                || properties.routingBaseUrl() == null || properties.routingBaseUrl().isBlank()
                || properties.courierBaseCompensationMinor() <= 0
                || properties.courierCompensationPerKilometerMinor() < 0
                || properties.courierLocationMaxAgeSeconds() <= 0 || properties.dispatchBatchSize() < 1) {
            throw new ServiceUnavailableException("Delivery routing, service area and fare configuration are incomplete");
        }
    }

    private static long multiplyAndAdd(long base, long perUnit, long units, String message) {
        try { return Math.addExact(base, Math.multiplyExact(perUnit, units)); }
        catch (ArithmeticException ex) { throw new ServiceUnavailableException(message); }
    }

    private static String proofCode() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : bytes) result.append(String.format("%02x", b));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
