package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Collections;
import java.util.List;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleValidation_returnsProblemDetailWithFieldErrorsAndTaxonomy() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email", "must be a well-formed email address"));

        MethodParameter methodParameter = new MethodParameter(
                TestController.class.getDeclaredMethod("submit", String.class), 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter, bindingResult);

        HttpServletRequest request = new StubHttpServletRequest("/api/users");
        var response = handler.handleValidation(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/validation"));
        assertThat(response.getInstance()).isEqualTo(URI.create("/api/users"));
        assertThat(response.getProperties().get("errorCode")).isEqualTo("VAL-001");
        assertThat(response.getProperties().get("category")).isEqualTo("validation");
        assertThat(response.getProperties()).doesNotContainKey("timestamp");
        assertThat(response.getProperties().get("fieldErrors")).isEqualTo(
                List.of(new ApiErrorPayload.FieldError("email", "must be a well-formed email address")));
    }

    @Test
    void handleValidation_includesTraceIdFromCorrelationIdHeader() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email", "must be a well-formed email address"));

        MethodParameter methodParameter = new MethodParameter(
                TestController.class.getDeclaredMethod("submit", String.class), 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter, bindingResult);

        HttpServletRequest request = new StubHttpServletRequest("/api/users", "trace-123");
        var response = handler.handleValidation(ex, request);

        assertThat(response.getProperties().get("traceId")).isEqualTo("trace-123");
    }

    @Test
    void handleApiProblemDetail_preservesDomainExceptionTaxonomy() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Listing", 123L);

        HttpServletRequest request = new StubHttpServletRequest("/api/listings/123");
        var response = handler.handleApiProblemDetail(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/not-found"));
        assertThat(response.getTitle()).isEqualTo("Not Found");
        assertThat(response.getInstance()).isEqualTo(URI.create("/api/listings/123"));
        assertThat(response.getProperties().get("errorCode")).isEqualTo("NF-001");
        assertThat(response.getProperties().get("category")).isEqualTo("not-found");
    }

    @Test
    void handleApiProblemDetail_preservesConflictTaxonomy() {
        ConflictException ex = new ConflictException("Cannot transition from OPEN to OPEN");

        HttpServletRequest request = new StubHttpServletRequest("/api/disputes/1/status");
        var response = handler.handleApiProblemDetail(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/conflict"));
        assertThat(response.getTitle()).isEqualTo("Conflict");
        assertThat(response.getProperties().get("errorCode")).isEqualTo("CONFLICT-001");
        assertThat(response.getProperties().get("category")).isEqualTo("conflict");
    }

    @Test
    void handleApiProblemDetail_preservesBadRequestTaxonomy() {
        BadRequestException ex = new BadRequestException("Cannot review a booking that is not COMPLETED");

        HttpServletRequest request = new StubHttpServletRequest("/api/reviews");
        var response = handler.handleApiProblemDetail(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/validation"));
        assertThat(response.getTitle()).isEqualTo("Bad Request");
        assertThat(response.getProperties().get("errorCode")).isEqualTo("VAL-001");
        assertThat(response.getProperties().get("category")).isEqualTo("validation");
    }

    @Test
    void handleConstraintViolation_returnsProblemDetail() {
        var ex = new ConstraintViolationException("must not be null", Collections.emptySet());
        var request = new StubHttpServletRequest("/api/users");
        var response = handler.handleConstraintViolation(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/validation"));
        assertThat(response.getTitle()).isEqualTo("Bad Request");
        assertThat(response.getProperties().get("errorCode")).isEqualTo("VAL-001");
        assertThat(response.getProperties().get("category")).isEqualTo("validation");
    }

    @Test
    void handleOptimisticLock_returnsConflict() {
        var ex = new ObjectOptimisticLockingFailureException("Booking", 1L);
        var request = new StubHttpServletRequest("/api/bookings/1");
        var response = handler.handleOptimisticLock(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/conflict"));
        assertThat(response.getProperties().get("errorCode")).isEqualTo("CONFLICT-001");
        assertThat(response.getProperties().get("category")).isEqualTo("conflict");
    }

    @Test
    void handleAccessDenied_returnsForbidden() {
        var ex = new AccessDeniedException("Access denied");
        var request = new StubHttpServletRequest("/api/bookings/1");
        var response = handler.handleAccessDenied(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/access-denied"));
        assertThat(response.getProperties().get("errorCode")).isEqualTo("AUTHZ-001");
        assertThat(response.getProperties().get("category")).isEqualTo("authz");
    }

    @Test
    void handleAuthentication_returnsUnauthorized() {
        var ex = new AuthenticationException("Authentication required") {};
        var request = new StubHttpServletRequest("/api/bookings/1");
        var response = handler.handleAuthentication(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/unauthorized"));
        assertThat(response.getProperties().get("errorCode")).isEqualTo("AUTHN-001");
        assertThat(response.getProperties().get("category")).isEqualTo("authz");
    }

    @Test
    void handleNoResource_returnsNotFound() {
        var ex = new NoResourceFoundException(HttpMethod.GET, "/api/listings/999", "Resource not found");
        var request = new StubHttpServletRequest("/api/listings/999");
        var response = handler.handleNoResource(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/not-found"));
        assertThat(response.getProperties().get("errorCode")).isEqualTo("NF-001");
        assertThat(response.getProperties().get("category")).isEqualTo("not-found");
    }

    @Test
    void handleRateLimited_returnsTooManyRequests() {
        var ex = RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("test"));
        var request = new StubHttpServletRequest("/api/listings");
        var response = handler.handleRateLimited(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/rate-limited"));
        assertThat(response.getProperties().get("errorCode")).isEqualTo("RL-001");
        assertThat(response.getProperties().get("category")).isEqualTo("rate-limit");
    }

    @Test
    void handleCircuitBreakerOpen_returnsServiceUnavailable() {
        var ex = CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("test"));
        var request = new StubHttpServletRequest("/api/bookings");
        var response = handler.handleCircuitBreakerOpen(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/service-unavailable"));
        assertThat(response.getProperties().get("errorCode")).isEqualTo("SU-001");
        assertThat(response.getProperties().get("category")).isEqualTo("availability");
    }

    @Test
    void handleGeneral_returnsInternalError() {
        var ex = new RuntimeException("Unexpected error");
        var request = new StubHttpServletRequest("/api/bookings");
        var response = handler.handleGeneral(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/internal-error"));
        assertThat(response.getProperties().get("errorCode")).isEqualTo("INT-001");
        assertThat(response.getProperties().get("category")).isEqualTo("internal");
    }

    static class TestController {
        public void submit(String email) {
        }
    }
}
