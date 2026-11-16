package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * A-03 (compliance plan 0.9 — the three-layer ordered composition): the
 * uncaught-exception safety net and its ordering.
 *
 * <p><b>The measured regression this advice exists to prevent:</b> when the
 * single house advice (specific handlers + an {@code Exception.class}
 * catch-all) was ordered ahead of Boot's automatic {@code
 * ProblemDetailsExceptionHandler} ({@code @Order(0)}), the catch-all — first
 * in the resolution chain — swallowed every built-in Spring MVC exception:
 * a POST on a GET-only path answered 500 instead of the automatic 405. The
 * official reference prescribes the takeover advice for <em>specific</em>
 * built-ins only, so the catch-all rides its own advice, ordered LAST —
 * behind the automatic handler — and the built-ins reach the official
 * automatic rendering.
 */
class GlobalErrorFallbackHandlerTest {

    private final GlobalErrorFallbackHandler handler = new GlobalErrorFallbackHandler();

    /**
     * The ordering that makes the composition work: the fallback sits at
     * {@link Ordered#LOWEST_PRECEDENCE} — after the house specific advice
     * (HIGHEST) and after Boot's automatic order-0 handler.
     */
    @Test
    void fallbackIsOrderedLastBehindTheAutomaticHandler_a03() {
        Order order = GlobalErrorFallbackHandler.class.getAnnotation(Order.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    /**
     * An unexpected exception is masked behind the 500 INTERNAL problem body
     * — the same wire contract the pre-split catch-all produced (same
     * rendering machinery, {@code ProblemDetailRendering}).
     */
    @Test
    void handleUncaught_returnsInternalError_a03() {
        var ex = new RuntimeException("Unexpected error");
        var request = new StubHttpServletRequest("/api/bookings");
        ProblemDetail response = handler.handleUncaught(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/internal-error"));
        assertThat(response.getProperties().get("errorCode")).isEqualTo("INT-001");
        assertThat(response.getProperties().get("category")).isEqualTo("internal");
    }
}
