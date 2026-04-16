package com.marketplace.shared.resilience;

import com.marketplace.booking.BookingService;
import com.marketplace.catalog.CatalogController;
import com.marketplace.payments.PaymentsService;
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
            Method method = PaymentsService.class.getMethod("processIntent", java.util.UUID.class);

            Retry retry = method.getAnnotation(Retry.class);
            CircuitBreaker cb = method.getAnnotation(CircuitBreaker.class);

            assertNotNull(retry, "processIntent should have @Retry");
            assertEquals("paymentProcessing", retry.name(), "Retry should use paymentProcessing instance");
            assertNotNull(cb, "processIntent should have @CircuitBreaker");
            assertEquals("paymentProcessing", cb.name(), "CircuitBreaker should use paymentProcessing instance");
            assertFalse(cb.fallbackMethod().isEmpty(), "CircuitBreaker should have a fallback method");
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
        @DisplayName("processIntentFallback should exist as private method")
        void processIntentFallback_exists() {
            List<Method> fallbacks = Arrays.stream(PaymentsService.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals("processIntentFallback"))
                    .toList();

            assertFalse(fallbacks.isEmpty(), "processIntentFallback should exist");
            assertTrue(Arrays.stream(fallbacks.getFirst().getParameterTypes())
                    .anyMatch(t -> Throwable.class.isAssignableFrom(t)),
                    "Fallback method should accept Throwable parameter");
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
        @DisplayName("search should have @RateLimiter")
        void search_hasRateLimiter() throws NoSuchMethodException {
            Method method = SearchController.class.getMethod("search",
                    String.class, String.class, org.springframework.data.domain.Pageable.class);

            RateLimiter rl = method.getAnnotation(RateLimiter.class);
            assertNotNull(rl, "search should have @RateLimiter");
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
}