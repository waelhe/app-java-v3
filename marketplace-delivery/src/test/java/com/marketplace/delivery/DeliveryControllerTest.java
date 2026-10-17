package com.marketplace.delivery;

import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The controller seams of the direct-delivery vertical slice: every surface
 * resolves the caller through CurrentUserProvider (never a client-supplied
 * id), delegates to the service/courier seams, and answers with the promised
 * status codes (201 for creations, 200 for reads and state changes).
 */
@ExtendWith(MockitoExtension.class)
class DeliveryControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private DeliveryService service;
    @Mock private DeliveryCourierService courierService;
    @Mock private CurrentUserProvider users;
    @Mock private Authentication authentication;
    @InjectMocks private DeliveryController controller;

    @Test
    void quoteResolvesTheRequesterAndAnswersCreated() {
        UUID requesterId = UUID.randomUUID();
        DeliveryQuoteResponse expected = quoteResponse();
        when(users.getCurrentUserId(authentication)).thenReturn(requesterId);
        CreateDeliveryQuoteRequest request = new CreateDeliveryQuoteRequest(
                "A", 0, 0, "B", 0, 0.1, "small box");
        when(service.createQuote(requesterId, request)).thenReturn(expected);

        ResponseEntity<DeliveryQuoteResponse> response =
                controller.quote(request, authentication);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertSame(expected, response.getBody());
    }

    @Test
    void quoteOrderResolvesTheBuyerAndAnswersCreated() {
        UUID buyerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        DeliveryQuoteResponse expected = quoteResponse();
        when(users.getCurrentUserId(authentication)).thenReturn(buyerId);
        CreateOrderDeliveryQuoteRequest request =
                new CreateOrderDeliveryQuoteRequest("Buyer address", 38.0, -121.0, "Small parcel");
        when(service.createOrderQuote(buyerId, orderId, request)).thenReturn(expected);

        ResponseEntity<DeliveryQuoteResponse> response =
                controller.quoteOrder(orderId, request, authentication);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertSame(expected, response.getBody());
    }

    @Test
    void createDelegatesTheQuoteIdOnly() {
        UUID requesterId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        DeliveryRequestResponse expected = requestResponse();
        when(users.getCurrentUserId(authentication)).thenReturn(requesterId);
        when(service.createRequest(requesterId, quoteId)).thenReturn(expected);

        ResponseEntity<DeliveryRequestResponse> response = controller.create(
                new CreateDeliveryRequest(quoteId), authentication);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertSame(expected, response.getBody());
    }

    @Test
    void mineWrapsTheServicePage() {
        when(users.getCurrentUserId(authentication)).thenReturn(USER_ID);
        when(service.listMine(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(requestResponse())));

        ResponseEntity<PagedResponse<DeliveryRequestResponse>> response =
                controller.mine(PageRequest.of(0, 20), authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().content().size());
    }

    @Test
    void getCombinesUserIdentityAndAdminFlag() {
        UUID id = UUID.randomUUID();
        DeliveryRequestResponse expected = requestResponse();
        when(users.getCurrentUserId(authentication)).thenReturn(USER_ID);
        when(users.isAdmin(authentication)).thenReturn(false);
        when(service.get(id, USER_ID, false)).thenReturn(expected);

        ResponseEntity<DeliveryRequestResponse> response = controller.get(id, authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(expected, response.getBody());
    }

    @Test
    void adminQueueNeedsNoIdentity() {
        when(service.listAdmin(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(requestResponse())));

        ResponseEntity<PagedResponse<DeliveryRequestResponse>> response =
                controller.queue(PageRequest.of(0, 50));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(service).listAdmin(any(Pageable.class));
    }

    @Test
    void updateAvailabilityResolvesTheCallingProvider() {
        DeliveryCourierResponse expected = new DeliveryCourierResponse(
                USER_ID, true, 37.0, -122.0, null, null, null);
        when(users.getCurrentUserId(authentication)).thenReturn(USER_ID);
        CourierAvailabilityRequest request = new CourierAvailabilityRequest(37.0, -122.0, true);
        when(courierService.updateAvailability(USER_ID, request)).thenReturn(expected);

        ResponseEntity<DeliveryCourierResponse> response =
                controller.updateAvailability(request, authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(expected, response.getBody());
    }

    @Test
    void courierDeliveriesAreKeyedByTheCaller() {
        when(users.getCurrentUserId(authentication)).thenReturn(USER_ID);
        when(service.listForCourier(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<PagedResponse<DeliveryRequestResponse>> response =
                controller.courierDeliveries(PageRequest.of(0, 20), authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().content().isEmpty());
    }

    @Test
    void cancelForwardsTheReasonOnly() {
        UUID id = UUID.randomUUID();
        DeliveryRequestResponse expected = requestResponse();
        when(users.getCurrentUserId(authentication)).thenReturn(USER_ID);
        when(service.cancelForRequester(id, USER_ID, "Changed my mind")).thenReturn(expected);

        ResponseEntity<DeliveryRequestResponse> response = controller.cancel(
                id, new CancelDeliveryRequest("Changed my mind"), authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(expected, response.getBody());
    }

    @Test
    void transitionForwardsTargetReasonAndProof() {
        UUID id = UUID.randomUUID();
        DeliveryRequestResponse expected = requestResponse();
        when(users.getCurrentUserId(authentication)).thenReturn(USER_ID);
        when(service.transition(id, USER_ID, DeliveryRequestStatus.PICKED_UP, "collected", null))
                .thenReturn(expected);

        ResponseEntity<DeliveryRequestResponse> response = controller.transition(
                id, new TransitionDeliveryRequest(DeliveryRequestStatus.PICKED_UP, "collected", null), authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(expected, response.getBody());
    }

    @Test
    void nullReasonsSurviveTheForwarding() {
        UUID id = UUID.randomUUID();
        DeliveryRequestResponse expected = requestResponse();
        when(users.getCurrentUserId(authentication)).thenReturn(USER_ID);
        when(service.transition(eq(id), eq(USER_ID), eq(DeliveryRequestStatus.PICKED_UP),
                isNull(), isNull())).thenReturn(expected);

        ResponseEntity<DeliveryRequestResponse> response = controller.transition(
                id, new TransitionDeliveryRequest(DeliveryRequestStatus.PICKED_UP, null, null), authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(expected, response.getBody());
    }

    private DeliveryQuoteResponse quoteResponse() {
        return new DeliveryQuoteResponse(UUID.randomUUID(), null, 12_500, 1_020,
                750, 375, "SAR", java.time.Instant.now().plusSeconds(900));
    }

    private DeliveryRequestResponse requestResponse() {
        return new DeliveryRequestResponse(UUID.randomUUID(), UUID.randomUUID(), null, null,
                DeliveryRequestStatus.AWAITING_PAYMENT, 750, 375, "SAR", 12_500, 1_020,
                "A", "B", "small box", null, null, null, null, null, null);
    }
}
