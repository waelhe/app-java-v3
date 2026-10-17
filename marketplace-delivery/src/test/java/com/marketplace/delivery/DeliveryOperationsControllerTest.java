package com.marketplace.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The V229 operations controller's tests — the domain-module house pattern
 * (direct invocation with the services mocked): every command answers its
 * read-back, every family answers its board, and the reconciliation window
 * carries both instants to the service verbatim.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryOperationsControllerTest {

    @Mock
    private DispatchQueueService dispatchQueues;
    @Mock
    private CourierOperationsService couriers;
    @Mock
    private CarrierRegistry carriers;
    @Mock
    private DeliveryReconciliationService reconciliation;

    @InjectMocks
    private DeliveryOperationsController controller;

    private final UUID courierId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(dispatchQueues.snapshot())
                .thenReturn(new DispatchQueueService.DispatchQueuesSnapshot(
                        Map.of(DeliveryRequestStatus.READY_FOR_DISPATCH, 3L), 1L));
        org.mockito.Mockito.lenient().when(couriers.roster(PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of()));
        org.mockito.Mockito.lenient().when(carriers.all()).thenReturn(List.of());
    }

    @Test
    void theQueuesSnapshotAnswersTheBoard() {
        ResponseEntity<DispatchQueueService.DispatchQueuesSnapshot> result = controller.queues();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().activeByState())
                .containsEntry(DeliveryRequestStatus.READY_FOR_DISPATCH, 3L);
        assertThat(result.getBody().atRiskPromises()).isEqualTo(1L);
    }

    @Test
    void theQueuePagesOneStatesWorkList() {
        when(dispatchQueues.queue(DeliveryRequestStatus.IN_TRANSIT, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(controller.queue(DeliveryRequestStatus.IN_TRANSIT, PageRequest.of(0, 20))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(dispatchQueues).queue(DeliveryRequestStatus.IN_TRANSIT, PageRequest.of(0, 20));
    }

    @Test
    void suspensionCarriesTheReasonAndAnswersTheReadBack() {
        when(couriers.suspend(courierId, "safety incident")).thenReturn(
                new CourierOperationsService.CourierStatusResponse(courierId,
                        CourierStatus.SUSPENDED, false, null, null, null, null));

        ResponseEntity<CourierOperationsService.CourierStatusResponse> result =
                controller.suspend(courierId, new DeliveryOperationsController.SuspendRequest(
                        "safety incident"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().status()).isEqualTo(CourierStatus.SUSPENDED);
        assertThat(result.getBody().available()).isFalse();
    }

    @Test
    void reactivationAnswersTheReadBack() {
        when(couriers.reactivate(courierId)).thenReturn(
                new CourierOperationsService.CourierStatusResponse(courierId,
                        CourierStatus.ACTIVE, false, null, null, null, null));

        assertThat(controller.reactivate(courierId).getBody().status())
                .isEqualTo(CourierStatus.ACTIVE);
    }

    @Test
    void theRosterPagesTheBoard() {
        assertThat(controller.roster(PageRequest.of(0, 20)).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(couriers).roster(PageRequest.of(0, 20));
    }

    @Test
    void theCarrierRegistryAnswersItsChannelsWithCapabilities() {
        DeliveryProperties properties = new DeliveryProperties(
                true, "SAR", 500L, 100L, 50_000, 900L, "http://routing", 200L, 50L, 120L, 25, 3L);
        PlatformFleetCarrier platform = new PlatformFleetCarrier(
                (a, b, c, d) -> new DeliveryRouteMetrics(1_000, 60), properties);
        when(carriers.all()).thenReturn(List.of(platform));

        ResponseEntity<List<DeliveryOperationsController.CarrierResponse>> result =
                controller.carriers();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).hasSize(1);
        assertThat(result.getBody().get(0).code()).isEqualTo("PLATFORM");
        assertThat(result.getBody().get(0).capabilities()).contains("ON_DEMAND");
    }

    @Test
    void theReconciliationCarriesTheWindowVerbatim() {
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now();
        DeliveryReconciliationService.ReconciliationReport report =
                new DeliveryReconciliationService.ReconciliationReport(from, to, 0,
                        List.of(), List.of(), List.of(), List.of());
        when(reconciliation.reconcile(from, to)).thenReturn(report);

        ResponseEntity<DeliveryReconciliationService.ReconciliationReport> result =
                controller.reconciliation(from, to);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().legs()).isZero();
        verify(reconciliation).reconcile(from, to);
    }
}
