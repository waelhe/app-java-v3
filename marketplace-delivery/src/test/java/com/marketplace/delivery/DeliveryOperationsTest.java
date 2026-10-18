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
import static org.mockito.Mockito.verifyNoInteractions;
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
                true, "SAR", 500L, 100L, 50_000, 900L, "http://routing", 200L, 50L, 120L, 25, 3L, 92L);

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

        private CourierOperationsService service;

        @BeforeEach
        void setUp() {
            service = new CourierOperationsService(couriers);
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
            // the verdict's own words ride the row (the Envers revision
            // history carries the who/when) — CodeRabbit round 1: the
            // courier-scoped note never rides delivery_request_events
            assertThat(suspended.statusReason()).isEqualTo("safety incident");
            assertThat(courier.getStatusReason()).isEqualTo("safety incident");
        }

        @Test
        void anIdleCourierSuspendsWithoutAnyDeliveryRequestEvent() {
            // CodeRabbit round 1, verified real (the Major): the idle courier
            // has NO current delivery request — the old shape wrote a
            // DeliveryRequestEvent whose delivery_request_id is NOT NULL by
            // V215's design, so the insert violated the constraint and rolled
            // the whole suspension back. The row-carried reason needs no
            // request at all.
            UUID userId = UUID.randomUUID();
            DeliveryCourier idle = new DeliveryCourier(userId);
            when(couriers.findByUserIdForUpdate(userId)).thenReturn(java.util.Optional.of(idle));
            when(couriers.save(any(DeliveryCourier.class))).thenAnswer(inv -> inv.getArgument(0));

            CourierOperationsService.CourierStatusResponse suspended = service.suspend(userId,
                    "no current job");

            assertThat(suspended.status()).isEqualTo(CourierStatus.SUSPENDED);
            assertThat(suspended.currentDeliveryRequestId()).isNull();
            assertThat(suspended.statusReason()).isEqualTo("no current job");
        }

        @Test
        void aSecondSuspensionAnswersTheHonestConflict() {
            UUID userId = UUID.randomUUID();
            DeliveryCourier courier = new DeliveryCourier(userId);
            courier.suspend("first");
            when(couriers.findByUserIdForUpdate(userId)).thenReturn(java.util.Optional.of(courier));

            assertThatThrownBy(() -> service.suspend(userId, "again"))
                    .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                    .hasMessageContaining("already suspended");
        }

        @Test
        void reactivationReturnsTheCourierToTheOperatorsGrace() {
            UUID userId = UUID.randomUUID();
            DeliveryCourier courier = new DeliveryCourier(userId);
            courier.updateAvailability(24.7, 46.7, true, Instant.now());
            courier.suspend("safety incident");
            when(couriers.findByUserIdForUpdate(userId)).thenReturn(java.util.Optional.of(courier));
            when(couriers.save(any(DeliveryCourier.class))).thenAnswer(inv -> inv.getArgument(0));

            CourierOperationsService.CourierStatusResponse reactivated = service.reactivate(userId);
            assertThat(reactivated.status()).isEqualTo(CourierStatus.ACTIVE);
            // availability stays the courier's OWN live signal — re-declared,
            // never auto-restored by the reactivation itself; the verdict's
            // reason is cleared with the verdict
            assertThat(reactivated.available()).isFalse();
            assertThat(reactivated.statusReason()).isNull();
        }

        @Test
        void aSuspendedCourierCannotDeclareAvailabilityOrTakeWork() {
            DeliveryCourier courier = new DeliveryCourier(UUID.randomUUID());
            courier.suspend("verdict");

            assertThatThrownBy(() -> courier.updateAvailability(24.7, 46.7, true, Instant.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("suspended courier cannot declare availability");
            assertThatThrownBy(() -> courier.assign(UUID.randomUUID(), Instant.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("suspended courier cannot take new work");
        }

        @Test
        void aLegFinishingUnderSuspensionNeverRestoresAvailability() {
            // CodeRabbit round 1, verified real (the Major): the suspended
            // courier's in-flight leg finishes — finish() must respect the
            // suspension and keep the courier unavailable until the operator
            // reactivates AND the courier re-declares availability.
            DeliveryCourier courier = new DeliveryCourier(UUID.randomUUID());
            courier.updateAvailability(24.7, 46.7, true, Instant.now());
            UUID requestId = UUID.randomUUID();
            courier.assign(requestId, Instant.now());
            courier.suspend("mid-route verdict");

            courier.finish(requestId, true, Instant.now(), 120L);

            assertThat(courier.getCurrentDeliveryRequestId()).isNull();
            assertThat(courier.isAvailable()).isFalse();
            assertThat(courier.getStatus()).isEqualTo(CourierStatus.SUSPENDED);
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
                true, "SAR", 500L, 100L, 50_000, 900L, "http://routing", 200L, 50L, 120L, 25, 3L, 92L);
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
                false, "SAR", 500L, 100L, 50_000, 900L, "http://routing", 200L, 50L, 120L, 25, 3L, 92L);
        PlatformFleetCarrier platform = new PlatformFleetCarrier(
                (a, b, c, d) -> { throw new RuntimeException("routing outage"); }, properties);

        assertThat(platform.serviceable(24.7, 46.7, 24.8, 46.8)).isFalse();
    }

    @Test
    void theFleetDeclaresItsImplementedCapabilitiesOnly() {
        // CodeRabbit round 1, verified real: the current path quotes and
        // executes on-demand legs and closes them with the proof-code
        // verification — SCHEDULED, LIVE_TRACKING and RETURNS are declared
        // when (and only when) a carrier actually implements them
        DeliveryProperties properties = new DeliveryProperties(
                true, "SAR", 500L, 100L, 50_000, 900L, "http://routing", 200L, 50L, 120L, 25, 3L, 92L);
        PlatformFleetCarrier platform = new PlatformFleetCarrier(
                (a, b, c, d) -> new DeliveryRouteMetrics(10_000, 600), properties);

        assertThat(platform.capabilities()).containsExactlyInAnyOrder(
                CarrierChannel.CarrierCapability.ON_DEMAND,
                CarrierChannel.CarrierCapability.PROOF_OF_DELIVERY);
    }

    @Test
    void duplicateCarrierCodesAreALoudConfigurationError() {
        // CodeRabbit round 1, verified real: a duplicate code must fail the
        // registry's construction — a silent put would replace the earlier
        // channel, hide it from all(), and answer only the later one from
        // byCode()
        DeliveryProperties properties = new DeliveryProperties(
                true, "SAR", 500L, 100L, 50_000, 900L, "http://routing", 200L, 50L, 120L, 25, 3L, 92L);
        PlatformFleetCarrier first = new PlatformFleetCarrier(
                (a, b, c, d) -> new DeliveryRouteMetrics(10_000, 600), properties);
        PlatformFleetCarrier second = new PlatformFleetCarrier(
                (a, b, c, d) -> new DeliveryRouteMetrics(20_000, 600), properties);

        assertThatThrownBy(() -> new CarrierRegistry(java.util.List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate carrier code 'PLATFORM'");
    }

    // ---------------- the settlement reconciliation ----------------

    @ExtendWith(MockitoExtension.class)
    static class DeliveryReconciliationServiceTest {

        @Mock
        private DeliveryRequestRepository requests;
        @Mock
        private com.marketplace.shared.api.PaymentIntentLookupPort paymentIntents;

        private DeliveryReconciliationService service;
        private final DeliveryProperties properties = new DeliveryProperties(
                true, "SAR", 500L, 100L, 50_000, 900L, "http://routing", 200L, 50L, 120L, 25, 3L, 92L);

        @BeforeEach
        void setUp() {
            service = new DeliveryReconciliationService(requests, paymentIntents, properties);
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
            // the window's intents resolve in ONE batched port read —
            // CodeRabbit round 1: never the per-leg findById N+1
            when(paymentIntents.findAllById(any())).thenReturn(java.util.List.of(
                    new com.marketplace.shared.api.PaymentIntentDetails(
                            collectedIntent, null, null, null, "SUCCEEDED", "DELIVERY",
                            1_000L, "SAR", null, null, null, null, 0L),
                    new com.marketplace.shared.api.PaymentIntentDetails(
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
            // the batched read is the ONE port call for the whole window
            verify(paymentIntents).findAllById(any());
            verify(paymentIntents, never()).findById(any());
        }

        @Test
        void anInvertedWindowAnswersTheHonest400() {
            // CodeRabbit round 1: an empty/inverted window is a client input
            // error — never a silently empty 200 report
            Instant from = Instant.now();
            Instant to = from.minusSeconds(3600);

            assertThatThrownBy(() -> service.reconcile(from, to))
                    .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                    .hasMessageContaining("must be before");
            verifyNoInteractions(requests);
        }

        @Test
        void anOversizedWindowAnswersTheHonest400() {
            // CodeRabbit round 1: the report is an in-memory operational read —
            // the span is bounded by the configured maximum (92 days here)
            Instant from = Instant.parse("2026-01-01T00:00:00Z");
            Instant to = Instant.parse("2026-10-01T00:00:00Z");

            assertThatThrownBy(() -> service.reconcile(from, to))
                    .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                    .hasMessageContaining("exceeds the configured maximum");
            verifyNoInteractions(requests);
        }
    }
}
