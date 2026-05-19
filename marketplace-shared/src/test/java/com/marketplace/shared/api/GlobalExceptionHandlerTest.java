package com.marketplace.shared.api;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldMapRateLimitTo429WithStandardType() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/payments/intents");

        ProblemDetail pd = handler.handleRateLimited(RequestNotPermitted.createRequestNotPermitted(null), request);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(pd.getType().toString()).isEqualTo("https://marketplace.com/errors/rate-limited");
        assertThat(pd.getInstance().toString()).isEqualTo("/api/payments/intents");
    }

    @Test
    void shouldMapCircuitBreakerOpenTo503WithStandardType() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/payments/intents/abc/process");

        CallNotPermittedException ex = CallNotPermittedException.createCallNotPermittedException(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("paymentProcessing")
        );

        ProblemDetail pd = handler.handleCircuitBreakerOpen(ex, request);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(pd.getType().toString()).isEqualTo("https://marketplace.com/errors/circuit-breaker-open");
        assertThat(pd.getInstance().toString()).isEqualTo("/api/payments/intents/abc/process");
    }

    @Test
    void shouldMapIllegalArgumentTo400WithStandardType() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/messaging/messages");

        ProblemDetail pd = handler.handleIllegalArgument(new IllegalArgumentException("invalid payload"), request);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getType().toString()).isEqualTo("https://marketplace.com/errors/bad-request");
        assertThat(pd.getDetail()).isEqualTo("invalid payload");
    }
}
