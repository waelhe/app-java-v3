package com.marketplace.shared.resilience;

import com.marketplace.booking.BookingService;
import com.marketplace.catalog.CatalogController;
import com.marketplace.disputes.DisputeService;
import com.marketplace.media.MediaController;
import com.marketplace.orders.OrderReturnsService;
import com.marketplace.payments.PaymentsService;
import com.marketplace.reviews.ReviewsController;
import com.marketplace.search.SearchController;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that Resilience4j annotations are properly applied
 * across services and controllers.
 */
class ResilienceAnnotationTest {

    @Nested
    @DisplayName("PaymentsService Resilience")
    class PaymentsServiceResilience {

        @Test
        @DisplayName("processIntent should have @Retry and @CircuitBreaker")
        void processIntent_hasRetryAndCircuitBreaker() throws NoSuchMethodException {
            Method method = PaymentsService.class.getMethod("processIntent",
                    java.util.UUID.class, Authentication.class);

            Retry retry = method.getAnnotation(Retry.class);
            CircuitBreaker cb = method.getAnnotation(CircuitBreaker.class);

            assertNotNull(retry, "processIntent should have @Retry");
            assertEquals("paymentProcessing", retry.name(), "Retry should use paymentProcessing instance");
            assertNotNull(cb, "processIntent should have @CircuitBreaker");
            assertEquals("paymentProcessing", cb.name(), "CircuitBreaker should use paymentProcessing instance");
            assertTrue(cb.fallbackMethod().isEmpty(), "CircuitBreaker should not declare a fallback method");
        }

        @Test
        @DisplayName("settlement confirm should have @Retry (N2: moved off the admin command shell)")
        void confirmIntent_hasRetry() throws NoSuchMethodException {
            // N2 moved the domain transition (and its resilience + observability)
            // into PaymentIntentSettlementService: the admin command shell
            // PaymentsService.confirmIntent carries ONLY the role check, and
            // the retry now covers BOTH entry paths (admin command + verified
            // webhook dispatch) because every call goes through the proxy.
            Method method = com.marketplace.payments.PaymentIntentSettlementService.class
                    .getMethod("confirm", java.util.UUID.class, String.class);

            Retry retry = method.getAnnotation(Retry.class);
            assertNotNull(retry, "settlement confirm should have @Retry");
            assertEquals("paymentProcessing", retry.name(), "Retry should use paymentProcessing instance");
        }

        @Test
        @DisplayName("refundPayment should have @Retry")
        void refundPayment_hasRetry() throws NoSuchMethodException {
            Method method = PaymentsService.class.getMethod("refundPayment", java.util.UUID.class);

            Retry retry = method.getAnnotation(Retry.class);
            assertNotNull(retry, "refundPayment should have @Retry");
            assertEquals("paymentProcessing", retry.name(), "Retry should use paymentProcessing instance");
        }

        @Test
        @DisplayName("processIntentFallback should not exist")
        void processIntentFallback_doesNotExist() {
            List<Method> fallbacks = Arrays.stream(PaymentsService.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("processIntentFallback"))
                    .toList();

            assertTrue(fallbacks.isEmpty(), "processIntentFallback should not exist");
        }
    }

    @Nested
    @DisplayName("BookingService Resilience")
    class BookingServiceResilience {

        @Test
        @DisplayName("confirm should have @Retry")
        void confirm_hasRetry() throws NoSuchMethodException {
            Method method = BookingService.class.getMethod("confirm", java.util.UUID.class, Authentication.class);

            Retry retry = method.getAnnotation(Retry.class);
            assertNotNull(retry, "confirm should have @Retry");
            assertEquals("booking", retry.name(), "Retry should use booking instance");
        }

        @Test
        @DisplayName("complete should have @Retry")
        void complete_hasRetry() throws NoSuchMethodException {
            Method method = BookingService.class.getMethod("complete", java.util.UUID.class, Authentication.class);

            Retry retry = method.getAnnotation(Retry.class);
            assertNotNull(retry, "complete should have @Retry");
            assertEquals("booking", retry.name(), "Retry should use booking instance");
        }
    }

    @Nested
    @DisplayName("CatalogController Rate Limiting")
    class CatalogControllerRateLimiting {

        @Test
        @DisplayName("listActive should have @RateLimiter")
        void listActive_hasRateLimiter() throws NoSuchMethodException {
            Method method = CatalogController.class.getMethod("listActive",
                    org.springframework.data.domain.Pageable.class);

            RateLimiter rl = method.getAnnotation(RateLimiter.class);
            assertNotNull(rl, "listActive should have @RateLimiter");
            assertEquals("catalog", rl.name(), "RateLimiter should use catalog instance");
        }

        @Test
        @DisplayName("listByCategory should have @RateLimiter")
        void listByCategory_hasRateLimiter() throws NoSuchMethodException {
            Method method = CatalogController.class.getMethod("listByCategory",
                    String.class, org.springframework.data.domain.Pageable.class);

            RateLimiter rl = method.getAnnotation(RateLimiter.class);
            assertNotNull(rl, "listByCategory should have @RateLimiter");
        }

        @Test
        @DisplayName("getById should have @RateLimiter")
        void getById_hasRateLimiter() throws NoSuchMethodException {
            // L40: the public read point gained the HttpServletRequest
            // parameter (the view counter's remote-address seam, the
            // LeadsController pattern) — the limiter still guards it.
            Method method = CatalogController.class.getMethod("getById", java.util.UUID.class,
                    jakarta.servlet.http.HttpServletRequest.class);

            RateLimiter rl = method.getAnnotation(RateLimiter.class);
            assertNotNull(rl, "getById should have @RateLimiter");
        }
    }

    @Nested
    @DisplayName("SearchController Rate Limiting")
    class SearchControllerRateLimiting {

        @Test
        @DisplayName("searchWithCriteria should have @RateLimiter")
        void searchWithCriteria_hasRateLimiter() throws NoSuchMethodException {
            // L27: the signature gained the stay-window params (checkIn/checkOut)
            // — the guard follows the live contract, exactly as it did for the
            // B4 currency-era signature changes. I6: it gained the guests param
            // — same rule: the guard tracks the live controller surface.
            // L32: the real-estate facets (locationId/purpose/propertyType/
            // minRooms/minBathrooms/minAreaM2). P1 (postgis): the radius
            // triple (lat/lng/radiusKm) — same rule again. W3 (yelp): the
            // min-stars floor (minRating) — same rule once more.
            Method method = SearchController.class.getMethod("searchWithCriteria",
                    String.class, String.class, java.math.BigDecimal.class, java.math.BigDecimal.class,
                    java.time.Instant.class, java.time.Instant.class,
                    Integer.class,
                    java.util.UUID.class,
                    com.marketplace.shared.api.PropertyPurpose.class,
                    com.marketplace.shared.api.PropertyType.class,
                    Integer.class, Integer.class, Integer.class,
                    java.math.BigDecimal.class, java.math.BigDecimal.class, java.math.BigDecimal.class,
                    java.math.BigDecimal.class,
                    org.springframework.data.domain.Pageable.class);

            RateLimiter rl = method.getAnnotation(RateLimiter.class);
            assertNotNull(rl, "searchWithCriteria should have @RateLimiter");
            assertEquals("search", rl.name(), "RateLimiter should use search instance");
        }

        @Test
        @DisplayName("searchByCategory should have @RateLimiter")
        void searchByCategory_hasRateLimiter() throws NoSuchMethodException {
            Method method = SearchController.class.getMethod("searchByCategory",
                    String.class, org.springframework.data.domain.Pageable.class);

            RateLimiter rl = method.getAnnotation(RateLimiter.class);
            assertNotNull(rl, "searchByCategory should have @RateLimiter");
        }
    }

    @Nested
    @DisplayName("Public Write Endpoints Rate Limiting (L29)")
    class PublicWriteEndpointsRateLimiting {

        @Test
        @DisplayName("BookingController.create should have @RateLimiter(bookingCreate)")
        void bookingCreate_hasRateLimiter() throws NoSuchMethodException {
            Method method = com.marketplace.booking.BookingController.class.getMethod("create",
                    com.marketplace.booking.BookingController.CreateBookingRequest.class,
                    Authentication.class);

            RateLimiter rl = method.getAnnotation(RateLimiter.class);
            assertNotNull(rl, "create should have @RateLimiter");
            assertEquals("bookingCreate", rl.name(),
                    "RateLimiter should use the independent bookingCreate instance");
        }

        @Test
        @DisplayName("ReviewsController.create should have @RateLimiter(reviewCreate)")
        void reviewCreate_hasRateLimiter() throws NoSuchMethodException {
            Method method = com.marketplace.reviews.ReviewsController.class.getMethod("create",
                    com.marketplace.reviews.ReviewsController.CreateReviewRequest.class,
                    Authentication.class);

            RateLimiter rl = method.getAnnotation(RateLimiter.class);
            assertNotNull(rl, "create should have @RateLimiter");
            assertEquals("reviewCreate", rl.name(),
                    "RateLimiter should use the independent reviewCreate instance");
        }

        @Test
        @DisplayName("ReviewsController.createReverse should have @RateLimiter(reviewCreate) — I8")
        void reviewCreateReverse_hasRateLimiter() throws NoSuchMethodException {
            // I8: the reverse write shares the reviewCreate budget — a review
            // write is a review write (the L29 three-instance policy extended
            // by the fourth surface on the same instance).
            Method method = com.marketplace.reviews.ReviewsController.class.getMethod("createReverse",
                    com.marketplace.reviews.ReviewsController.CreateReviewRequest.class,
                    Authentication.class);

            RateLimiter rl = method.getAnnotation(RateLimiter.class);
            assertNotNull(rl, "createReverse should have @RateLimiter");
            assertEquals("reviewCreate", rl.name(),
                    "RateLimiter should share the reviewCreate instance");
        }

        @Test
        @DisplayName("MediaController.requestUpload should have @RateLimiter(mediaUpload)")
        void mediaUpload_hasRateLimiter() throws NoSuchMethodException {
            Method method = com.marketplace.media.MediaController.class.getMethod("requestUpload",
                    com.marketplace.media.MediaController.RequestUploadRequest.class,
                    Authentication.class);

            RateLimiter rl = method.getAnnotation(RateLimiter.class);
            assertNotNull(rl, "requestUpload should have @RateLimiter");
            assertEquals("mediaUpload", rl.name(),
                    "RateLimiter should use the independent mediaUpload instance");
        }

        @Test
        @DisplayName("SavedSearchController.create should have @RateLimiter(savedSearchCreate) — L35")
        void savedSearchCreate_hasRateLimiter() throws NoSuchMethodException {
            // L35 (realestate systems plan §5): the saved-search write joins
            // the named-instance policy — the fifth high-impact write
            // surface (the L29 model, the L34 leadCreate sibling).
            Method method = com.marketplace.search.SavedSearchController.class.getMethod("create",
                    com.marketplace.search.SavedSearchController.SavedSearchCreateRequest.class,
                    Authentication.class);

            RateLimiter rl = method.getAnnotation(RateLimiter.class);
            assertNotNull(rl, "create should have @RateLimiter");
            assertEquals("savedSearchCreate", rl.name(),
                    "RateLimiter should use the independent savedSearchCreate instance");
        }
    }

    @Nested
    @DisplayName("Refund Carriers Bulkhead Gate (A4 verification 2026-11-28)")
    class RefundCarriersBulkheadGate {

        /**
         * The adversarial verification leg on #828 (2026-11-28): the shared
         * refundCarriers gate rests entirely on three annotations — none of
         * which any test pinned before this one. A silently dropped or
         * renamed annotation (the "config that lies" class this file
         * guards against for @Retry/@CircuitBreaker/@RateLimiter) would
         * leave the round-5 rationale's arithmetic unenforced: the three
         * refund-carrying carrier entries would again sum to ~15 held
         * connections against the 20-connection production pool. The pin
         * is the named-instance discipline resilience4j documents — the
         * SEMAPHORE bulkhead with a shared name is the ONE gate every
         * cross-class entry rides.
         */
        @Test
        @DisplayName("OrderReturnsService.complete should have @Bulkhead(refundCarriers, SEMAPHORE)")
        void orderReturnCompletion_ridesTheSharedRefundCarriersGate() throws NoSuchMethodException {
            Method method = OrderReturnsService.class.getMethod("complete", java.util.UUID.class);

            Bulkhead gate = method.getAnnotation(Bulkhead.class);
            assertNotNull(gate, "complete should have @Bulkhead");
            assertEquals("refundCarriers", gate.name(),
                    "Bulkhead should use the shared refundCarriers instance");
            assertEquals(Bulkhead.Type.SEMAPHORE, gate.type(),
                    "the carrier transaction gate is a semaphore bulkhead, never a thread pool");
        }

        @Test
        @DisplayName("DisputeService.resolve (3-arg shell) should have @Bulkhead(refundCarriers, SEMAPHORE)")
        void disputeResolveShell_ridesTheSharedRefundCarriersGate() throws NoSuchMethodException {
            // The 3-arg shell delegates to the 4-arg resolve — round 5 put
            // the gate on BOTH entries so no call path bypasses the shared
            // semaphore.
            Method method = DisputeService.class.getMethod("resolve",
                    java.util.UUID.class,
                    com.marketplace.shared.api.DisputeResolution.class,
                    Authentication.class);

            Bulkhead gate = method.getAnnotation(Bulkhead.class);
            assertNotNull(gate, "the 3-arg resolve should have @Bulkhead");
            assertEquals("refundCarriers", gate.name(),
                    "Bulkhead should use the shared refundCarriers instance");
            assertEquals(Bulkhead.Type.SEMAPHORE, gate.type());
        }

        @Test
        @DisplayName("DisputeService.resolve (4-arg, refund-carrying) should have @Bulkhead(refundCarriers, SEMAPHORE)")
        void disputeResolveRefunding_ridesTheSharedRefundCarriersGate() throws NoSuchMethodException {
            Method method = DisputeService.class.getMethod("resolve",
                    java.util.UUID.class,
                    com.marketplace.shared.api.DisputeResolution.class,
                    Long.class,
                    Authentication.class);

            Bulkhead gate = method.getAnnotation(Bulkhead.class);
            assertNotNull(gate, "the 4-arg resolve should have @Bulkhead");
            assertEquals("refundCarriers", gate.name(),
                    "Bulkhead should use the shared refundCarriers instance");
            assertEquals(Bulkhead.Type.SEMAPHORE, gate.type());
        }

        /**
         * The A4 core annotation pin: the carrier overload's retry attempts
         * must own their transactions. The behavioral twin
         * (RefundCarrierPoisoningIntegrationTest) replays the poisoning on
         * real PostgreSQL; this pin guards the annotation itself — a
         * reverted propagation (REQUIRED) would pass unnoticed at the
         * annotation layer while the integration replay only runs under
         * Docker. Spring Framework Reference, Declarative Transaction
         * Management: a runtime exception crossing a participating REQUIRED
         * scope marks the whole shared transaction rollback-only.
         */
        @Test
        @DisplayName("refundPayment (carrier overload) should run REQUIRES_NEW")
        void carrierRefundOverload_ownsItsRetryTransaction() throws NoSuchMethodException {
            Method method = PaymentsService.class.getMethod("refundPayment",
                    java.util.UUID.class, Long.class, PaymentsService.StableRefundScope.class);

            Retry retry = method.getAnnotation(Retry.class);
            assertNotNull(retry, "the carrier overload should have @Retry");
            assertEquals("paymentProcessing", retry.name());

            Transactional transactional = method.getAnnotation(Transactional.class);
            assertNotNull(transactional,
                    "the carrier overload should have @Transactional");
            assertEquals(Propagation.REQUIRES_NEW, transactional.propagation(),
                    "each retry attempt must own its transaction (the A4 fix)");
        }
    }
}
