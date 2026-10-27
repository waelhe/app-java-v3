package com.marketplace.delivery;

import com.marketplace.shared.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {
    @Mock DeliveryQuoteRepository quotes;
    @Mock DeliveryRequestRepository requests;
    @Mock DeliveryRequestEventRepository events;
    @Mock DeliveryPaymentPort payments;
    @Mock PaymentIntentLookupPort lookup;
    @Mock ApplicationEventPublisher publisher;
    @Mock DeliveryRouteProvider routes;
    @Mock DeliveryCourierRepository couriers;
    @Mock ProviderLookupPort providers;
    @Mock ProviderFulfillmentPort fulfillmentLocations;
    @Mock OrderDeliveryPort orders;

    private DeliveryService service(DeliveryProperties properties) {
        return new DeliveryService(properties, quotes, requests, events, payments, lookup, publisher,
                routes, couriers, providers, fulfillmentLocations, orders);
    }

    private DeliveryProperties enabledProperties() {
        return new DeliveryProperties(true, "SAR", 100, 50, 20000, 900,
                "https://routing.example.invalid", 50, 25, 120, 25, 3L, 92L);
    }

    private DeliveryProperties disabledProperties() {
        return new DeliveryProperties(false, "SAR", 0, 0, 0, 900,
                "", 0, 0, 120, 25, 3L, 92L);
    }

    @Test
    void disabledByDefaultRefusesToQuoteWithoutCallingRouter() {
        var service = service(disabledProperties());
        assertThrows(ServiceUnavailableException.class, () -> service.createQuote(UUID.randomUUID(),
                new CreateDeliveryQuoteRequest("A", 1, 1, "B", 1, 1.01, "small box")));
        verifyNoInteractions(quotes, payments, routes);
    }

    @Test
    void quoteUsesRoadRouteMetricsAndServerComputedFareAndCompensation() {
        when(routes.route(0, 0, 0, 0.1)).thenReturn(new DeliveryRouteMetrics(12_500, 1_020));
        when(quotes.save(any(DeliveryQuote.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var response = service(enabledProperties()).createQuote(UUID.randomUUID(),
                new CreateDeliveryQuoteRequest("A", 0, 0, "B", 0, 0.1, "small box"));

        assertEquals(12_500, response.distanceMeters());
        assertEquals(1_020, response.estimatedDurationSeconds());
        assertEquals("SAR", response.currency());
        assertEquals(750, response.amountMinor()); // 100 + 13 km x 50
        assertEquals(375, response.courierCompensationMinor()); // 50 + 13 km x 25

        ArgumentCaptor<DeliveryQuote> saved = ArgumentCaptor.forClass(DeliveryQuote.class);
        verify(quotes).save(saved.capture());
        assertEquals(12_500, saved.getValue().getDistanceMeters());
        assertEquals(1_020, saved.getValue().getRouteDurationSeconds());
        assertEquals(375, saved.getValue().getCourierCompensationMinor());
    }

    @Test
    void orderQuoteUsesVerifiedSellerPickupAndLinksTheConfirmedOrder() {
        UUID buyer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID sellerUserId = UUID.randomUUID();
        when(orders.requireConfirmedOrderForDelivery(orderId, buyer))
                .thenReturn(new OrderDeliveryDetails(orderId, buyer, sellerUserId, "SAR", null));
        // the V224 guard's read: this legacy order carries no checkout
        // selection — the manual add-on quoting path stays open for it
        when(orders.requireOrderFulfillment(orderId, buyer))
                .thenReturn(new com.marketplace.shared.api.OrderFulfillmentSelection(
                        orderId, buyer, sellerUserId, "SAR", null, null, null, null, 0, null));
        when(fulfillmentLocations.findVerifiedLocation(sellerUserId))
                .thenReturn(Optional.of(new ProviderFulfillmentLocation(sellerUserId,
                        "Seller depot", 37.0, -122.0)));
        when(routes.route(37.0, -122.0, 38.0, -121.0))
                .thenReturn(new DeliveryRouteMetrics(12_500, 1_020));
        when(quotes.save(any(DeliveryQuote.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DeliveryQuoteResponse response = service(enabledProperties()).createOrderQuote(buyer,
                orderId, new CreateOrderDeliveryQuoteRequest("Buyer address", 38.0, -121.0, "Small parcel"));

        assertEquals(orderId, response.orderId());
        assertEquals(750, response.amountMinor());
        ArgumentCaptor<DeliveryQuote> saved = ArgumentCaptor.forClass(DeliveryQuote.class);
        verify(quotes).save(saved.capture());
        assertEquals("Seller depot", saved.getValue().getPickupAddress());
        assertEquals(sellerUserId, orders.requireConfirmedOrderForDelivery(orderId, buyer).sellerId());
        assertEquals(orderId, saved.getValue().getOrderId());
        verify(routes).route(37.0, -122.0, 38.0, -121.0);
    }

    @Test
    void refusesOrderDeliveryWhenSellerPickupIsNotConfigured() {
        UUID buyer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID sellerUserId = UUID.randomUUID();
        when(orders.requireConfirmedOrderForDelivery(orderId, buyer))
                .thenReturn(new OrderDeliveryDetails(orderId, buyer, sellerUserId, "SAR", null));
        // the V224 guard's read: this legacy order carries no checkout
        // selection — the manual add-on quoting path stays open for it
        when(orders.requireOrderFulfillment(orderId, buyer))
                .thenReturn(new com.marketplace.shared.api.OrderFulfillmentSelection(
                        orderId, buyer, sellerUserId, "SAR", null, null, null, null, 0, null));
        when(fulfillmentLocations.findVerifiedLocation(sellerUserId)).thenReturn(Optional.empty());

        assertThrows(ConflictException.class, () -> service(enabledProperties()).createOrderQuote(buyer,
                orderId, new CreateOrderDeliveryQuoteRequest("Buyer address", 38.0, -121.0, "Small parcel")));
        verifyNoInteractions(routes, quotes);
    }

    @Test
    void refusesToQuoteWhenCompensationConsumesTheEntireFare() {
        DeliveryProperties properties = new DeliveryProperties(true, "SAR", 100, 50, 20000, 900,
                "https://routing.example.invalid", 1000, 50, 120, 25, 3L, 92L);
        when(routes.route(0, 0, 0, 0.1)).thenReturn(new DeliveryRouteMetrics(12_500, 1_020));

        assertThrows(ServiceUnavailableException.class, () -> service(properties).createQuote(UUID.randomUUID(),
                new CreateDeliveryQuoteRequest("A", 0, 0, "B", 0, 0.1, "small box")));
        verifyNoInteractions(quotes);
    }

    @Test
    void routeOutsideConfiguredServiceAreaDoesNotCreateQuote() {
        when(routes.route(0, 0, 0, 0.1)).thenReturn(new DeliveryRouteMetrics(30_000, 1_800));
        assertThrows(ConflictException.class, () -> service(enabledProperties()).createQuote(UUID.randomUUID(),
                new CreateDeliveryQuoteRequest("A", 0, 0, "B", 0, 0.1, "small box")));
        verifyNoInteractions(quotes);
    }

    @Test
    void completedPaymentMovesDeliveryRequestIntoDispatchQueue() {
        UUID requesterId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        DeliveryQuote quote = new DeliveryQuote(UUID.randomUUID(), requesterId,
                "Pickup", 37.0, -122.0, "Dropoff", 38.0, -121.0, "Small parcel",
                12_500, 1_020, 750, 375, "SAR", java.time.Instant.now().plusSeconds(600));
        DeliveryRequest request = new DeliveryRequest(requestId, quote, "proof-hash");
        request.attachPaymentIntent(paymentId);
        when(lookup.findById(paymentId)).thenReturn(Optional.of(new PaymentIntentDetails(
                paymentId, null, requesterId, null, "SUCCEEDED", "DELIVERY", 750, "SAR",
                null, null, null, requestId, 0L)));
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        when(requests.save(any(DeliveryRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(events.save(any(DeliveryRequestEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service(enabledProperties()).onPaymentStateChanged(
                new PaymentStateChangedEvent(paymentId, "COMPLETED"));

        assertEquals(DeliveryRequestStatus.READY_FOR_DISPATCH, request.getStatus());
        verify(requests).save(request);
        verify(events).save(any(DeliveryRequestEvent.class));
        verifyNoInteractions(couriers);
    }

    @Test
    void paymentEventsIgnoreAnotherModuleOrigin() {
        UUID paymentId = UUID.randomUUID();
        when(lookup.findById(paymentId)).thenReturn(Optional.of(
                new PaymentIntentDetails(paymentId, UUID.randomUUID(), UUID.randomUUID(), null,
                        "COMPLETED", "BOOKING", 1000, "SAR", null, null)));
        service(enabledProperties()).onPaymentStateChanged(new PaymentStateChangedEvent(paymentId, "COMPLETED"));
        verifyNoInteractions(requests, publisher);
    }

    // ---- createRequest: the quote-to-request lifecycle ----

    private DeliveryQuote liveQuote(UUID requesterId, UUID orderId) {
        return new DeliveryQuote(UUID.randomUUID(), requesterId, "Pickup", 37, -122,
                "Dropoff", 38, -121, "Parcel", 12_000, 900, 500, 200, "SAR",
                Instant.now().plusSeconds(900), orderId);
    }

    @Test
    void createRequestIsIdempotentForTheSameRequester() {
        UUID requesterId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        DeliveryQuote quote = liveQuote(requesterId, null);
        DeliveryRequest existing = new DeliveryRequest(UUID.randomUUID(), quote, "hash");
        when(requests.findByQuoteId(quoteId)).thenReturn(Optional.of(existing));

        DeliveryRequestResponse response = service(enabledProperties()).createRequest(requesterId, quoteId);

        assertEquals(existing.getId(), response.id());
        assertNull(response.proofCode());
        verifyNoInteractions(payments, orders, quotes);
    }

    @Test
    void createRequestHidesForeignQuotes() {
        UUID quoteId = UUID.randomUUID();
        DeliveryQuote foreign = liveQuote(UUID.randomUUID(), null);
        when(requests.findByQuoteId(quoteId)).thenReturn(Optional.of(
                new DeliveryRequest(UUID.randomUUID(), foreign, "hash")));

        assertThrows(ResourceNotFoundException.class, () ->
                service(enabledProperties()).createRequest(UUID.randomUUID(), quoteId));
    }

    @Test
    void createRequestRefusesAMissingQuote() {
        when(requests.findByQuoteId(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(quotes.findByIdForUpdate(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service(enabledProperties()).createRequest(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void createRequestRefusesAForeignUnconsumedQuote() {
        UUID requesterId = UUID.randomUUID();
        DeliveryQuote quote = liveQuote(UUID.randomUUID(), null);
        when(requests.findByQuoteId(quote.getId())).thenReturn(Optional.empty());
        when(quotes.findByIdForUpdate(quote.getId())).thenReturn(Optional.of(quote));

        assertThrows(ResourceNotFoundException.class, () ->
                service(enabledProperties()).createRequest(requesterId, quote.getId()));
    }

    @Test
    void createRequestReturnsTheRacedRequestWhenTheQuoteWasJustConsumed() {
        UUID requesterId = UUID.randomUUID();
        DeliveryQuote quote = liveQuote(requesterId, null);
        quote.consume(Instant.now());
        when(requests.findByQuoteId(quote.getId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new DeliveryRequest(UUID.randomUUID(), quote, "hash")));
        when(quotes.findByIdForUpdate(quote.getId())).thenReturn(Optional.of(quote));

        DeliveryRequestResponse response = service(enabledProperties()).createRequest(requesterId, quote.getId());

        assertNotNull(response.id());
        verifyNoInteractions(payments);
    }

    @Test
    void createRequestRefusesAnExpiredQuote() {
        UUID requesterId = UUID.randomUUID();
        DeliveryQuote expired = new DeliveryQuote(UUID.randomUUID(), requesterId, "Pickup", 37, -122,
                "Dropoff", 38, -121, "Parcel", 12_000, 900, 500, 200, "SAR",
                Instant.now().minusSeconds(1));
        when(requests.findByQuoteId(expired.getId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(quotes.findByIdForUpdate(expired.getId())).thenReturn(Optional.of(expired));

        assertThrows(ConflictException.class, () ->
                service(enabledProperties()).createRequest(requesterId, expired.getId()));
    }

    @Test
    void createRequestConsumesTheQuoteAttachesPaymentAndLinksTheOrder() {
        UUID requesterId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        DeliveryQuote quote = liveQuote(requesterId, orderId);
        UUID paymentIntentId = UUID.randomUUID();
        when(requests.findByQuoteId(quote.getId())).thenReturn(Optional.empty());
        when(quotes.findByIdForUpdate(quote.getId())).thenReturn(Optional.of(quote));
        when(requests.save(org.mockito.ArgumentMatchers.any(DeliveryRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(payments.createForDelivery(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(requesterId),
                org.mockito.ArgumentMatchers.eq(500L),
                org.mockito.ArgumentMatchers.eq("SAR")))
                .thenReturn(new PaymentIntentDetails(paymentIntentId, null, requesterId, null,
                        "REQUIRES_CAPTURE", "DELIVERY", 500, "SAR", null, null, null, null, 0));

        DeliveryRequestResponse response = service(enabledProperties()).createRequest(requesterId, quote.getId());

        assertEquals(DeliveryRequestStatus.AWAITING_PAYMENT, response.status());
        assertEquals(paymentIntentId, response.paymentIntentId());
        assertNotNull(response.proofCode());
        assertEquals(orderId, response.orderId());
        assertNotNull(quote.getConsumedAt());
        verify(orders).attachDeliveryRequest(orderId, requesterId, response.id());
        verify(quotes).save(quote);
    }

    // ---- the read surfaces ----

    @Test
    void listsAreKeyedByTheirAudience() {
        UUID requesterId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        DeliveryQuote quote = liveQuote(requesterId, null);
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), quote, "hash");
        org.springframework.data.domain.Pageable page =
                org.springframework.data.domain.PageRequest.of(0, 20);
        when(requests.findByRequesterIdOrderByCreatedAtDesc(requesterId, page))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(request)));
        verifiedCourier(courierId); // listForCourier answers only to a verified courier provider
        when(requests.findByCourierUserIdOrderByCreatedAtDesc(courierId, page))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));
        when(requests.findAllByOrderByCreatedAtDesc(page))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(request)));

        var service = service(enabledProperties());
        assertEquals(1, service.listMine(requesterId, page).getTotalElements());
        assertEquals(0, service.listForCourier(courierId, page).getTotalElements());
        assertEquals(1, service.listAdmin(page).getTotalElements());
    }

    @Test
    void courierHistoryIsDeniedToAnUnverifiedProvider() {
        UUID courierId = UUID.randomUUID();
        when(providers.findByUserId(courierId)).thenReturn(Optional.of(
                new com.marketplace.shared.api.ProviderSummary(
                        UUID.randomUUID(), "Courier", "PENDING", courierId)));

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                service(enabledProperties()).listForCourier(courierId,
                        org.springframework.data.domain.PageRequest.of(0, 20)));
    }

    @Test
    void getIsVisibleToTheAdminWithoutProviderChecks() {
        UUID requesterId = UUID.randomUUID();
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), liveQuote(requesterId, null), "hash");
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));

        var response = service(enabledProperties()).get(request.getId(), UUID.randomUUID(), true);

        assertEquals(request.getId(), response.id());
        verifyNoInteractions(providers);
    }

    @Test
    void getIsVisibleToTheRequester() {
        UUID requesterId = UUID.randomUUID();
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), liveQuote(requesterId, null), "hash");
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));

        assertEquals(request.getId(),
                service(enabledProperties()).get(request.getId(), requesterId, false).id());
    }

    @Test
    void getIsHiddenFromStrangers() {
        UUID requesterId = UUID.randomUUID();
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), liveQuote(requesterId, null), "hash");
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));

        assertThrows(ResourceNotFoundException.class, () ->
                service(enabledProperties()).get(request.getId(), UUID.randomUUID(), false));
    }

    @Test
    void getIsHiddenFromAnUnverifiedAssignedCourier() {
        UUID requesterId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        DeliveryQuote quote = liveQuote(requesterId, null);
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), quote, "hash");
        request.paymentSucceeded();
        request.assignCourier(courierId);
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        when(providers.findByUserId(courierId)).thenReturn(Optional.of(
                new com.marketplace.shared.api.ProviderSummary(
                        UUID.randomUUID(), "Courier", "PENDING", courierId)));

        assertThrows(ResourceNotFoundException.class, () ->
                service(enabledProperties()).get(request.getId(), courierId, false));
    }

    // ---- requester cancellation ----

    @Test
    void cancelIsIdempotentOnceCancelled() {
        UUID requesterId = UUID.randomUUID();
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), liveQuote(requesterId, null), "hash");
        request.cancel("Changed my mind");
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        var response = service(enabledProperties()).cancelForRequester(
                request.getId(), requesterId, "Retry");

        assertEquals(DeliveryRequestStatus.CANCELLED, response.status());
        verify(requests, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cancelHidesForeignRequests() {
        UUID requesterId = UUID.randomUUID();
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), liveQuote(requesterId, null), "hash");
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        assertThrows(ResourceNotFoundException.class, () ->
                service(enabledProperties()).cancelForRequester(request.getId(), UUID.randomUUID(), "reason"));
    }

    @Test
    void cancelAfterPickupBecomesAConflict() {
        UUID requesterId = UUID.randomUUID();
        DeliveryQuote quote = liveQuote(requesterId, null);
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), quote, "hash");
        request.paymentSucceeded();
        request.assignCourier(UUID.randomUUID());
        request.transition(DeliveryRequestStatus.PICKED_UP, null, Instant.now());
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        assertThrows(ConflictException.class, () ->
                service(enabledProperties()).cancelForRequester(request.getId(), requesterId, "Too late"));
    }

    @Test
    void cancelWithoutPaymentClearsTheOrderLink() {
        UUID requesterId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        DeliveryQuote quote = liveQuote(requesterId, orderId);
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), quote, "hash");
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(requests.save(request)).thenReturn(request);

        var response = service(enabledProperties()).cancelForRequester(
                request.getId(), requesterId, "Changed my mind");

        assertEquals(DeliveryRequestStatus.CANCELLED, response.status());
        verify(orders).clearDeliveryRequest(orderId, request.getId());
        verify(publisher, org.mockito.Mockito.never())
                .publishEvent(org.mockito.ArgumentMatchers.any(DeliveryRefundRequestedEvent.class));
    }

    @Test
    void cancelWithPaymentRequestsTheRefundAndReleasesTheCourier() {
        UUID requesterId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        DeliveryQuote quote = liveQuote(requesterId, orderId);
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), quote, "hash");
        request.paymentSucceeded();
        request.assignCourier(courierId);
        request.attachPaymentIntent(paymentIntentId);
        DeliveryCourier courier = new DeliveryCourier(courierId);
        courier.updateAvailability(37, -122, true, Instant.now());
        courier.assign(request.getId(), Instant.now());
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(requests.save(request)).thenReturn(request);
        when(couriers.findByUserIdForUpdate(courierId)).thenReturn(Optional.of(courier));
        when(couriers.save(courier)).thenReturn(courier);

        var response = service(enabledProperties()).cancelForRequester(
                request.getId(), requesterId, "Changed my mind");

        assertEquals(DeliveryRequestStatus.CANCELLED, response.status());
        assertNull(courier.getCurrentDeliveryRequestId());
        var eventCaptor = org.mockito.ArgumentCaptor.forClass(DeliveryRefundRequestedEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertEquals(request.getId(), eventCaptor.getValue().deliveryRequestId());
        assertEquals(orderId, eventCaptor.getValue().orderId());
    }

    // ---- courier transitions ----

    private DeliveryRequest assignedRequest(UUID requesterId, UUID courierId) {
        DeliveryQuote quote = liveQuote(requesterId, null);
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), quote, "hash");
        request.paymentSucceeded();
        request.assignCourier(courierId);
        return request;
    }

    private void verifiedCourier(UUID courierId) {
        when(providers.findByUserId(courierId)).thenReturn(Optional.of(
                new com.marketplace.shared.api.ProviderSummary(
                        UUID.randomUUID(), "Courier", "VERIFIED", courierId)));
    }

    @Test
    void transitionByAStrangerIsDenied() {
        UUID requesterId = UUID.randomUUID();
        DeliveryRequest request = assignedRequest(requesterId, UUID.randomUUID());
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                service(enabledProperties()).transition(request.getId(), UUID.randomUUID(),
                        DeliveryRequestStatus.PICKED_UP, "collected", null));
    }

    @Test
    void transitionByAnUnverifiedCourierIsDenied() {
        UUID requesterId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        DeliveryRequest request = assignedRequest(requesterId, courierId);
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(providers.findByUserId(courierId)).thenReturn(Optional.of(
                new com.marketplace.shared.api.ProviderSummary(
                        UUID.randomUUID(), "Courier", "PENDING", courierId)));

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                service(enabledProperties()).transition(request.getId(), courierId,
                        DeliveryRequestStatus.PICKED_UP, "collected", null));
    }

    @Test
    void deliveryWithoutProofIsDenied() {
        UUID requesterId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        DeliveryRequest request = assignedRequest(requesterId, courierId);
        request.transition(DeliveryRequestStatus.PICKED_UP, null, Instant.now());
        request.transition(DeliveryRequestStatus.IN_TRANSIT, null, Instant.now());
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        verifiedCourier(courierId);

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                service(enabledProperties()).transition(request.getId(), courierId,
                        DeliveryRequestStatus.DELIVERED, null, null));
    }

    @Test
    void deliveryWithTheWrongProofIsDenied() {
        UUID requesterId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        DeliveryRequest request = assignedRequest(requesterId, courierId);
        request.transition(DeliveryRequestStatus.PICKED_UP, null, Instant.now());
        request.transition(DeliveryRequestStatus.IN_TRANSIT, null, Instant.now());
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        verifiedCourier(courierId);

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                service(enabledProperties()).transition(request.getId(), courierId,
                        DeliveryRequestStatus.DELIVERED, null, "forged"));
    }

    @Test
    void anUnsupportedTargetIsAConflict() {
        UUID requesterId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        DeliveryRequest request = assignedRequest(requesterId, courierId);
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        verifiedCourier(courierId);

        assertThrows(ConflictException.class, () ->
                service(enabledProperties()).transition(request.getId(), courierId,
                        DeliveryRequestStatus.AWAITING_PAYMENT, null, null));
    }

    @Test
    void pickupSavesAndRecordsWithoutCourierRelease() {
        UUID requesterId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        DeliveryRequest request = assignedRequest(requesterId, courierId);
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(requests.save(request)).thenReturn(request);
        verifiedCourier(courierId);

        var response = service(enabledProperties()).transition(request.getId(), courierId,
                DeliveryRequestStatus.PICKED_UP, "collected", null);

        assertEquals(DeliveryRequestStatus.PICKED_UP, response.status());
        verify(requests).save(request);
        verifyNoInteractions(couriers);
        verify(publisher, org.mockito.Mockito.never())
                .publishEvent(org.mockito.ArgumentMatchers.any(DeliveryCompletedEvent.class));
    }

    @Test
    void deliveryWithTheRightProofReleasesTheCourierAndPublishesCompletion() {
        UUID requesterId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        String proof = "right-proof";
        // The service hashes the proof with SHA-256 before matching; precompute it.
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(),
                liveQuote(requesterId, null), sha256(proof));
        request.paymentSucceeded();
        request.assignCourier(courierId);
        request.attachPaymentIntent(paymentIntentId);
        request.transition(DeliveryRequestStatus.PICKED_UP, null, Instant.now());
        request.transition(DeliveryRequestStatus.IN_TRANSIT, null, Instant.now());
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(requests.save(request)).thenReturn(request);
        verifiedCourier(courierId);
        DeliveryCourier courier = new DeliveryCourier(courierId);
        courier.updateAvailability(37, -122, true, Instant.now());
        courier.assign(request.getId(), Instant.now());
        when(couriers.findByUserIdForUpdate(courierId)).thenReturn(Optional.of(courier));
        when(couriers.save(courier)).thenReturn(courier);

        var response = service(enabledProperties()).transition(request.getId(), courierId,
                DeliveryRequestStatus.DELIVERED, null, proof);

        assertEquals(DeliveryRequestStatus.DELIVERED, response.status());
        assertNull(courier.getCurrentDeliveryRequestId());
        var eventCaptor = org.mockito.ArgumentCaptor.forClass(DeliveryCompletedEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertEquals(request.getId(), eventCaptor.getValue().deliveryRequestId());
        assertEquals(paymentIntentId, eventCaptor.getValue().paymentIntentId());
    }

    @Test
    void failureWithReasonRequestsTheRefund() {
        UUID requesterId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        DeliveryRequest request = assignedRequest(requesterId, courierId);
        request.attachPaymentIntent(paymentIntentId);
        request.transition(DeliveryRequestStatus.PICKED_UP, null, Instant.now());
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(requests.save(request)).thenReturn(request);
        verifiedCourier(courierId);
        DeliveryCourier courier = new DeliveryCourier(courierId);
        courier.updateAvailability(37, -122, true, Instant.now());
        courier.assign(request.getId(), Instant.now());
        when(couriers.findByUserIdForUpdate(courierId)).thenReturn(Optional.of(courier));
        when(couriers.save(courier)).thenReturn(courier);

        var response = service(enabledProperties()).transition(request.getId(), courierId,
                DeliveryRequestStatus.FAILED, "package damaged", null);

        assertEquals(DeliveryRequestStatus.FAILED, response.status());
        var eventCaptor = org.mockito.ArgumentCaptor.forClass(DeliveryRefundRequestedEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertEquals("package damaged", eventCaptor.getValue().reason());
    }

    @Test
    void failureWithoutPaymentIntentClearsTheOrderLink() {
        // The part-1 review note's own scenario: the sole leg a fromCheckout
        // request creates carries no payment intent — its FAILED termination
        // must cut the order link exactly like the cancel path (no refund
        // event exists to request, and the buyer's order cancel stays
        // refused while the link survives — the reconciliation never
        // completes a leg-less aggregate either way).
        UUID requesterId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        DeliveryQuote quote = liveQuote(requesterId, orderId);
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), quote, "hash");
        request.paymentSucceeded();
        request.assignCourier(courierId);
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(requests.save(request)).thenReturn(request);
        verifiedCourier(courierId);
        DeliveryCourier courier = new DeliveryCourier(courierId);
        courier.updateAvailability(37, -122, true, Instant.now());
        courier.assign(request.getId(), Instant.now());
        when(couriers.findByUserIdForUpdate(courierId)).thenReturn(Optional.of(courier));
        when(couriers.save(courier)).thenReturn(courier);

        var response = service(enabledProperties()).transition(request.getId(), courierId,
                DeliveryRequestStatus.FAILED, "courier could not reach the pickup", null);

        assertEquals(DeliveryRequestStatus.FAILED, response.status());
        verify(orders).clearDeliveryRequest(orderId, request.getId());
        verify(publisher, org.mockito.Mockito.never())
                .publishEvent(org.mockito.ArgumentMatchers.any(DeliveryRefundRequestedEvent.class));
    }

    // ---- payment-state settlement ----

    private PaymentIntentDetails deliveryIntent(UUID paymentIntentId, UUID requesterId,
                                                DeliveryRequest request, String status) {
        return new PaymentIntentDetails(paymentIntentId, null, requesterId, null,
                status, "DELIVERY", request.getAmountMinor(), request.getCurrency(),
                null, null, null, request.getId(), 0);
    }

    @Test
    void uninterestingPaymentStatesAreIgnored() {
        service(enabledProperties()).onPaymentStateChanged(
                new PaymentStateChangedEvent(UUID.randomUUID(), "REQUIRES_CAPTURE"));
        verifyNoInteractions(lookup, requests, publisher);
    }

    @Test
    void anUnknownIntentIsAnInconsistency() {
        UUID paymentIntentId = UUID.randomUUID();
        when(lookup.findById(paymentIntentId)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () ->
                service(enabledProperties()).onPaymentStateChanged(
                        new PaymentStateChangedEvent(paymentIntentId, "COMPLETED")));
    }

    @Test
    void aDeliveryIntentWithoutRequestIdentityIsAnInconsistency() {
        UUID paymentIntentId = UUID.randomUUID();
        when(lookup.findById(paymentIntentId)).thenReturn(Optional.of(
                new PaymentIntentDetails(paymentIntentId, null, UUID.randomUUID(), null,
                        "SUCCEEDED", "DELIVERY", 500, "SAR", null, null, null, null, 0)));

        assertThrows(IllegalStateException.class, () ->
                service(enabledProperties()).onPaymentStateChanged(
                        new PaymentStateChangedEvent(paymentIntentId, "COMPLETED")));
    }

    @Test
    void aMismatchedPayableIsAnInconsistency() {
        UUID requesterId = UUID.randomUUID();
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), liveQuote(requesterId, null), "hash");
        UUID paymentIntentId = UUID.randomUUID();
        when(lookup.findById(paymentIntentId)).thenReturn(Optional.of(
                new PaymentIntentDetails(paymentIntentId, null, UUID.randomUUID(), null,
                        "SUCCEEDED", "DELIVERY", 999, "SAR", null, null, null, request.getId(), 0)));
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        assertThrows(IllegalStateException.class, () ->
                service(enabledProperties()).onPaymentStateChanged(
                        new PaymentStateChangedEvent(paymentIntentId, "COMPLETED")));
    }

    @Test
    void aCompletedEventWithoutASucceededIntentIsAnInconsistency() {
        UUID requesterId = UUID.randomUUID();
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), liveQuote(requesterId, null), "hash");
        UUID paymentIntentId = UUID.randomUUID();
        request.attachPaymentIntent(paymentIntentId);
        when(lookup.findById(paymentIntentId))
                .thenReturn(Optional.of(deliveryIntent(paymentIntentId, requesterId, request, "CAPTURE_PENDING")));
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        assertThrows(IllegalStateException.class, () ->
                service(enabledProperties()).onPaymentStateChanged(
                        new PaymentStateChangedEvent(paymentIntentId, "COMPLETED")));
    }

    @Test
    void aLateCompletionAfterFailureRequestsTheRefund() {
        UUID requesterId = UUID.randomUUID();
        DeliveryQuote quote = liveQuote(requesterId, null);
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), quote, "hash");
        UUID paymentIntentId = UUID.randomUUID();
        request.attachPaymentIntent(paymentIntentId);
        request.paymentFailed();
        when(lookup.findById(paymentIntentId))
                .thenReturn(Optional.of(deliveryIntent(paymentIntentId, requesterId, request, "SUCCEEDED")));
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        service(enabledProperties()).onPaymentStateChanged(
                new PaymentStateChangedEvent(paymentIntentId, "COMPLETED"));

        verify(publisher).publishEvent(org.mockito.ArgumentMatchers.any(DeliveryRefundRequestedEvent.class));
    }

    @Test
    void aFailedPaymentFailsTheRequestAndClearsTheOrderLink() {
        UUID requesterId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        DeliveryQuote quote = liveQuote(requesterId, orderId);
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), quote, "hash");
        UUID paymentIntentId = UUID.randomUUID();
        request.attachPaymentIntent(paymentIntentId);
        when(lookup.findById(paymentIntentId))
                .thenReturn(Optional.of(deliveryIntent(paymentIntentId, requesterId, request, "FAILED")));
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(requests.save(request)).thenReturn(request);

        service(enabledProperties()).onPaymentStateChanged(
                new PaymentStateChangedEvent(paymentIntentId, "FAILED"));

        assertEquals(DeliveryRequestStatus.PAYMENT_FAILED, request.getStatus());
        verify(orders).clearDeliveryRequest(orderId, request.getId());
    }

    @Test
    void aRefundAfterCancellationClearsTheOrderLink() {
        UUID requesterId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        DeliveryQuote quote = liveQuote(requesterId, orderId);
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), quote, "hash");
        UUID paymentIntentId = UUID.randomUUID();
        request.attachPaymentIntent(paymentIntentId);
        request.cancel("Changed my mind");
        when(lookup.findById(paymentIntentId))
                .thenReturn(Optional.of(deliveryIntent(paymentIntentId, requesterId, request, "REFUNDED")));
        when(requests.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));

        service(enabledProperties()).onPaymentStateChanged(
                new PaymentStateChangedEvent(paymentIntentId, "REFUNDED"));

        verify(orders).clearDeliveryRequest(orderId, request.getId());
        verify(requests, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : bytes) result.append(String.format("%02x", b));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void aCheckoutSelectedOrderRefusesTheManualAddOnQuote() {
        UUID buyer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID sellerUserId = UUID.randomUUID();
        when(orders.requireConfirmedOrderForDelivery(orderId, buyer))
                .thenReturn(new OrderDeliveryDetails(orderId, buyer, sellerUserId, "SAR", null));
        when(orders.requireOrderFulfillment(orderId, buyer))
                .thenReturn(new com.marketplace.shared.api.OrderFulfillmentSelection(
                        orderId, buyer, sellerUserId, "SAR", "PLATFORM_DELIVERY",
                        "Riyadh downtown", 24.7136, 46.6753, 1500, null));

        assertThrows(ConflictException.class, () -> service(enabledProperties()).createOrderQuote(
                buyer, orderId,
                new CreateOrderDeliveryQuoteRequest("Buyer address", 38.0, -121.0, "Small parcel")));
        verifyNoInteractions(routes, quotes);
    }

    @Test
    void aSplitCarvesThePrimaryShareAndNeverExceedsTheFrozenFee() {
        UUID orderId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID sellerUserId = UUID.randomUUID();
        UUID primaryId = UUID.randomUUID();
        // the frozen agreement: 1500 minor shipping, the primary leg carries
        // it all with a 100 compensation
        DeliveryRequest primary = DeliveryRequest.fromCheckout(primaryId, orderId, buyer,
                1500L, 100L, "SAR", 12_500L, 1_020L,
                "Seller depot", 37.0, -122.0, "Buyer address", 38.0, -121.0,
                "first package", "hash");
        when(requests.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .thenReturn(java.util.List.of(primary));
        // part-2 review round 2: the split now rides the LOCKING selection read
        when(orders.requireOrderFulfillmentForUpdate(orderId, buyer))
                .thenReturn(new com.marketplace.shared.api.OrderFulfillmentSelection(
                        orderId, buyer, sellerUserId, "SAR", "PLATFORM_DELIVERY",
                        "Buyer address", 38.0, -121.0, 1500L, null));
        when(fulfillmentLocations.findVerifiedLocation(sellerUserId))
                .thenReturn(Optional.of(new ProviderFulfillmentLocation(sellerUserId,
                        "Seller depot", 37.0, -122.0)));
        when(routes.route(37.0, -122.0, 38.0, -121.0))
                .thenReturn(new DeliveryRouteMetrics(12_500, 1_020));
        when(requests.save(any(DeliveryRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        DeliveryRequestResponse split = service(enabledProperties())
                .addLegForOrder(orderId, "second package", 400L);

        // the redistribution law: the new leg carries its 400 share, the
        // primary keeps 1100 — the legs' sum stays the buyer's frozen 1500
        assertThat(split.amountMinor()).isEqualTo(400L);
        assertThat(primary.getAmountMinor()).isEqualTo(1100L);
        assertThat(primary.getCourierCompensationMinor()).isEqualTo(100L);
    }

    @Test
    void aSplitRefusesTheLegacyQuotedOrder() {
        UUID orderId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        DeliveryRequest primary = DeliveryRequest.fromCheckout(UUID.randomUUID(), orderId, buyer,
                1500L, 100L, "SAR", 12_500L, 1_020L,
                "Seller depot", 37.0, -122.0, "Buyer address", 38.0, -121.0,
                "first package", "hash");
        when(requests.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .thenReturn(java.util.List.of(primary));
        // the legacy #604 shape: no checkout selection — its legs are
        // quote-backed; an unpaid add-on leg must never go out for dispatch
        // part-2 review round 2: the split now rides the LOCKING selection read
        when(orders.requireOrderFulfillmentForUpdate(orderId, buyer))
                .thenReturn(new com.marketplace.shared.api.OrderFulfillmentSelection(
                        orderId, buyer, UUID.randomUUID(), "SAR", null,
                        null, null, null, 0, null));

        assertThrows(ConflictException.class, () ->
                service(enabledProperties()).addLegForOrder(orderId, "second package", 400L));
        verifyNoInteractions(routes);
    }

    @Test
    void aSplitRefusesAShareThePrimaryMarginCannotCarve() {
        UUID orderId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID sellerUserId = UUID.randomUUID();
        DeliveryRequest primary = DeliveryRequest.fromCheckout(UUID.randomUUID(), orderId, buyer,
                1500L, 100L, "SAR", 12_500L, 1_020L,
                "Seller depot", 37.0, -122.0, "Buyer address", 38.0, -121.0,
                "first package", "hash");
        when(requests.findByOrderIdOrderByCreatedAtAscIdAsc(orderId))
                .thenReturn(java.util.List.of(primary));
        // part-2 review round 2: the split now rides the LOCKING selection read
        when(orders.requireOrderFulfillmentForUpdate(orderId, buyer))
                .thenReturn(new com.marketplace.shared.api.OrderFulfillmentSelection(
                        orderId, buyer, sellerUserId, "SAR", "PLATFORM_DELIVERY",
                        "Buyer address", 38.0, -121.0, 1500L, null));

        // 1450 leaves the primary 50 — below its own 100 compensation: the
        // platform never pays margin out of pocket
        assertThrows(ConflictException.class, () ->
                service(enabledProperties()).addLegForOrder(orderId, "greedy split", 1450L));
        verifyNoInteractions(routes);
    }
}
