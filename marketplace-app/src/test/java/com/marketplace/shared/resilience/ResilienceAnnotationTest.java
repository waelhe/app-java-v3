package com.marketplace.shared.resilience;

import com.marketplace.booking.BookingService;
import com.marketplace.catalog.CatalogController;
import com.marketplace.media.MediaController;
import com.marketplace.payments.PaymentsService;
import com.marketplace.reviews.ReviewsController;
import com.marketplace.search.SearchController;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        @DisplayName("confirmIntent should have @Retry")
        void confirmIntent_hasRetry() throws NoSuchMethodException {
            Method method = PaymentsService.class.getMethod("confirmIntent",
                    java.util.UUID.class, String.class);

            Retry retry = method.getAnnotation(Retry.class);
            assertNotNull(retry, "confirmIntent should have @Retry");
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
            Method method = CatalogController.class.getMethod("getById", java.util.UUID.class);

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
            Method method = SearchController.class.getMethod("searchWithCriteria",
                    String.class, String.class, java.math.BigDecimal.class, java.math.BigDecimal.class,
                    java.time.Instant.class, java.time.Instant.class,
                    Integer.class,
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
    }
}
