package com.marketplace.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The V229 delivery-operations tests (the architecture §7-Phase-4): the
 * dispatch board's snapshot and queues, the courier lifecycle's verdicts,
 * the carrier registry's boundary, and the settlement reconciliation's
 * two leg families kept honestly apart.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryOperationsTest {

    // ---------------- the dispatch board ----------------

    @ExtendWith(MockitoExtension.class)
    static class DispatchQueueServiceTest {

        @Mock
        private DeliveryRequestRepository requests;

        private DispatchQueueService service;
        private final DeliveryProperties properties = new DeliveryProperties(
                true, "SAR", 500L, 100L, 50_000, 900L, "http://routing", 200L, 50L, 120L, 25, 3L);

        @BeforeEach
        void setUp() {
            service = new DispatchQueueService(requests, properties);
        }

        @Test
        void theSnapshotCarriesEveryActiveStatesCountAndTheAtRiskExceptions() {
            when(requests.countActiveByStatus()).thenReturn(List.<Object[]>of(
                    new Object[]{DeliveryRequestStatus.READY_FOR_DISPATCH, 4L},
                    new Object[]{DeliveryRequestStatus.IN_TRANSIT, 2L}));
            when(requests.countAtRisk(org.mockito.ArgumentMatchers.eq(3L), any(Instant.class))).thenReturn(1L);

            DispatchQueueService.DispatchQueuesSnapshot snapshot = service.snapshot();

            assertThat(snapshot.activeByState())
                    .containsEntry(DeliveryRequestStatus.READY_FOR_DISPATCH, 4L)
                    .containsEntry(DeliveryRequestStatus.IN_TRANSIT, 2L)
                    .containsEntry(DeliveryRequestStatus.ASSIGNED, 0L);
            assertThat(snapshot.atRiskPromises()).isEqualTo(1L);
        }

        @Test
        void theQueuePagesOneStatesWorkList() {
            var page = new PageImpl<DeliveryRequest>(List.of());
            when(requests.findByStatusOrderByCreatedAtAscIdAsc(
                    DeliveryRequestStatus.READY_FOR_DISPATCH, PageRequest.of(0, 20)))
                    .thenReturn(page);

            assertThat(service.queue(DeliveryRequestStatus.READY_FOR_DISPATCH,
                    PageRequest.of(0, 20)).getTotalElements()).isZero();
        }
    }

    // ---------------- the courier lifecycle ----------------

    @ExtendWith(MockitoExtension.class)
    static class CourierOperationsServiceTest {

        @Mock
        private DeliveryCourierRepository couriers;
        @Mock
        private DeliveryRequestEventRepository events;

        private CourierOperationsService service;

        @BeforeEach
        void setUp() {
            service = new CourierOperationsService(couriers, events);
        }

        @Test
        void suspensionIsTheOperatorsVerdictAndKeepsTheInFlightLeg() {
            UUID userId = UUID.randomUUID();
            DeliveryCourier courier = new DeliveryCourier(userId);
            courier.updateAvailability(24.7, 46.7, true, Instant.now());
            when(couriers.findByUserIdForUpdate(userId)).thenReturn(java.util.Optional.of(courier));
            when(couriers.save(any(DeliveryCourier.class))).thenAnswer(inv -> inv.getArgument(0));

            CourierOperationsService.CourierStatusResponse suspended = service.suspend(userId,
                    "safety incident");

            assertThat(suspended.status()).isEqualTo(CourierStatus.SUSPENDED);
            assertThat(suspended.available()).isFalse();
            // the verdict event was recorded on the operational timeline
            verify(events).save(any(DeliveryRequestEvent.class));
        }

        @Test
        void aSecondSuspensionAnswersTheHonestConflict() {
            UUID userId = UUID.randomUUID();
            DeliveryCourier courier = new DeliveryCourier(userId);
            courier.suspend();
            when(couriers.findByUserIdForUpdate(userId)).thenReturn(java.util.Optional.of(courier));

            assertThatThrownBy(() -> service.suspend(userId, "again"))
                    .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                    .hasMessageContaining("already suspended");
        }

        @Test
        void reactivationReturnsTheCourierToTheOperatorsGrace() {
            UUID userId = UUID.randomUUID();
            DeliveryCourier courier = new DeliveryCourier(userId);
            courier.suspend();
            when(couriers.findByUserIdForUpdate(userId)).thenReturn(java.util.Optional.of(courier));
            when(couriers.save(any(DeliveryCourier.class))).thenAnswer(inv -> inv.getArgument(0));

            CourierOperationsService.CourierStatusResponse reactivated = service.reactivate(userId);
            assertThat(reactivated.status()).isEqualTo(CourierStatus.ACTIVE);
            // availability stays the courier's OWN live signal — re-declared, not forced
            assertThat(reactivated.available()).isFalse();
        }

        @Test
        void aSuspendedCourierCannotDeclareAvailabilityOrTakeWork() {
            DeliveryCourier courier = new DeliveryCourier(UUID.randomUUID());
            courier.suspend();

            assertThatThrownBy(() -> courier.updateAvailability(24.7, 46.7, true, Instant.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("suspended courier cannot declare availability");
            assertThatThrownBy(() -> courier.assign(UUID.randomUUID(), Instant.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("suspended courier cannot take new work");
        }

        @Test
        void theRosterPagesTheBoard() {
            when(couriers.findAllByOrderByUpdatedAtDesc(PageRequest.of(0, 20)))
                    .thenReturn(new PageImpl<>(List.of(new DeliveryCourier(UUID.randomUUID()))));

            assertThat(service.roster(PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
        }
    }

    // ---------------- the carrier boundary ----------------

    @Test
    void theRegistryExposesThePlatformFleetByItsStableCode() {
        DeliveryProperties properties = new DeliveryProperties(
                true, "SAR", 500L, 100L, 50_000, 900L, "http://routing", 200L, 50L, 120L, 25, 3L);
        DeliveryRouteProvider routes = new DeliveryRouteProvider() {
            @Override
            public DeliveryRouteMetrics route(double pickupLatitude, double pickupLongitude,
                                              double dropoffLatitude, double dropoffLongitude) {
                return new DeliveryRouteMetrics(10_000, 600);
            }
        };
        PlatformFleetCarrier platform = new PlatformFleetCarrier(routes, properties);
        CarrierRegistry registry = new CarrierRegistry(List.of(platform));

        assertThat(registry.byCode("PLATFORM")).isPresent();
        assertThat(registry.byCode("PLATFORM").orElseThrow().capabilities())
                .contains(CarrierChannel.CarrierCapability.ON_DEMAND,
                        CarrierChannel.CarrierCapability.PROOF_OF_DELIVERY);
        assertThat(platform.serviceable(24.7, 46.7, 24.8, 46.8)).isTrue(); // 10km < 50km
        assertThat(platform.code()).isEqualTo("PLATFORM");
    }

    @Test
    void anOutageOrDisabledServiceIsAnHonestNonServiceable() {
        DeliveryProperties properties = new DeliveryProperties(
                false, "SAR", 500L, 100L, 50_000, 900L, "http://routing", 200L, 50L, 120L, 25, 3L);
        PlatformFleetCarrier platform = new PlatformFleetCarrier(
                (a, b, c, d) -> { throw new RuntimeException("routing outage"); }, properties);

        assertThat(platform.serviceable(24.7, 46.7, 24.8, 46.8)).isFalse();
    }

    // ---------------- the settlement reconciliation ----------------

    @ExtendWith(MockitoExtension.class)
    static class DeliveryReconciliationServiceTest {

        @Mock
        private DeliveryRequestRepository requests;
        @Mock
        private com.marketplace.shared.api.PaymentIntentLookupPort paymentIntents;

        private DeliveryReconciliationService service;

        @BeforeEach
        void setUp() {
            service = new DeliveryReconciliationService(requests, paymentIntents);
        }

        private DeliveryRequest leg(String currency, long amount, long compensation,
                                    java.util.UUID paymentIntentId) {
            DeliveryQuote quote = new DeliveryQuote(UUID.randomUUID(), UUID.randomUUID(),
                    "depot", 24.7, 46.7, "home", 24.8, 46.8, "parcel", 10_000, 600,
                    amount, compensation, currency, Instant.now().plusSeconds(900), null);
            DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), quote, "hash");
            if (paymentIntentId != null) {
                request.attachPaymentIntent(paymentIntentId);
            }
            return request;
        }

        @Test
        void quoteBorneLegsCountTheirCollectedAmountsFromTheirOwnIntents() {
            java.util.UUID collectedIntent = java.util.UUID.randomUUID();
            DeliveryRequest collected = leg("SAR", 1_000L, 400L, collectedIntent);
            DeliveryRequest pending = leg("SAR", 2_000L, 800L, java.util.UUID.randomUUID());
            DeliveryRequest orderBorne = leg("SAR", 1_500L, 600L, null);
            when(requests.findByCreatedAtBetween(any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of(collected, pending, orderBorne));
            when(paymentIntents.findById(collectedIntent)).thenReturn(java.util.Optional.of(
                    new com.marketplace.shared.api.PaymentIntentDetails(
                            collectedIntent, null, null, null, "SUCCEEDED", "DELIVERY",
                            1_000L, "SAR", null, null, null, null, 0L)));
            when(paymentIntents.findById(pending.getPaymentIntentId()))
                    .thenReturn(java.util.Optional.of(new com.marketplace.shared.api.PaymentIntentDetails(
                            pending.getPaymentIntentId(), null, null, null, "CREATED", "DELIVERY",
                            2_000L, "SAR", null, null, null, null, 0L)));

            DeliveryReconciliationService.ReconciliationReport report =
                    service.reconcile(Instant.now().minusSeconds(3600), Instant.now());

            assertThat(report.legs()).isEqualTo(3);
            // quoted = all legs; collected = only the SUCCEEDED intent's leg
            assertThat(report.quoted()).containsExactly(
                    new DeliveryReconciliationService.CurrencyRow("SAR", 4_500L));
            assertThat(report.collected()).containsExactly(
                    new DeliveryReconciliationService.CurrencyRow("SAR", 1_000L));
            // the order-borne fee is listed APART — never double-counted
            assertThat(report.orderBorneQuoted()).containsExactly(
                    new DeliveryReconciliationService.CurrencyRow("SAR", 1_500L));
            // payables span both families (couriers ride every leg)
            assertThat(report.courierPayables()).containsExactly(
                    new DeliveryReconciliationService.CurrencyRow("SAR", 1_800L));
        }
    }
}
