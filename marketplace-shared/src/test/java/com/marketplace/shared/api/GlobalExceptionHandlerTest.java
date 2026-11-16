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
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.ErrorResponseException;
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
        // §5: no violations (the synthetic empty set) → the extension is an
        // empty list, still present — one shape on every 400.
        assertThat(response.getProperties().get("fieldErrors")).isEqualTo(List.of());
    }

    /**
     * §5 (platform-readiness audit — the fieldErrors row): the method-
     * validation leg answers the same fieldErrors extension as the body leg,
     * with the violation's leaf name (the parameter) as the field — the real
     * executable validator produces the exact production path shape
     * ("convert.from").
     */
    @Test
    void handleConstraintViolation_answersFieldErrorsWithTheLeafParameterName() throws NoSuchMethodException {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Method convert = Constrained.class.getDeclaredMethod("convert", String.class);
        var violations = validator.forExecutables()
                .validateParameters(new Constrained(), convert, new Object[]{""});
        var ex = new ConstraintViolationException(violations);
        var request = new StubHttpServletRequest("/api/v1/pricing/convert");

        var response = handler.handleConstraintViolation(ex, request);

        @SuppressWarnings("unchecked")
        var fieldErrors = (List<ApiErrorPayload.FieldError>) response.getProperties().get("fieldErrors");
        assertThat(fieldErrors).hasSize(1);
        assertThat(fieldErrors.getFirst().field()).isEqualTo("from");
        assertThat(fieldErrors.getFirst().message()).isEqualTo("must not be blank");
        assertThat(response.getProperties().get("errorCode")).isEqualTo("VAL-001");
    }

    static class Constrained {
        public void convert(@NotBlank String from) {}
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

    /**
     * A circuit-breaker CallNotPermittedException maps to 503 with the
     * UNAVAILABLE taxonomy.
     */
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

    /**
     * A5: IllegalArgumentException maps to 400 VALIDATION (VAL-001) as an
     * RFC 7807 problem.
     */
    @Test
    void handleIllegalArgument_returnsBadRequestValidationTaxonomy() {
        var ex = new IllegalArgumentException("priceCents must be positive");
        var request = new StubHttpServletRequest("/api/listings");
        var response = handler.handleIllegalArgument(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/validation"));
        assertThat(response.getTitle()).isEqualTo("Bad Request");
        assertThat(response.getProperties().get("errorCode")).isEqualTo("VAL-001");
        assertThat(response.getProperties().get("category")).isEqualTo("validation");
        assertThat(response.getDetail()).isEqualTo("priceCents must be positive");
    }

    /**
     * A5: IllegalStateException maps to 409 CONFLICT (CONFLICT-001) as an
     * RFC 7807 problem.
     */
    @Test
    void handleIllegalState_returnsConflictTaxonomy() {
        var ex = new IllegalStateException("Booking cannot be cancelled in current state");
        var request = new StubHttpServletRequest("/api/bookings/1");
        var response = handler.handleIllegalState(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/conflict"));
        assertThat(response.getTitle()).isEqualTo("Conflict");
        assertThat(response.getProperties().get("errorCode")).isEqualTo("CONFLICT-001");
        assertThat(response.getProperties().get("category")).isEqualTo("conflict");
        assertThat(response.getDetail()).isEqualTo("Booking cannot be cancelled in current state");
    }

    /**
     * A6: with no client header, the trace id comes from the
     * {@code correlationId} request attribute set by the filter.
     */
    @Test
    void problemDetail_includesTraceIdFromRequestAttributeWhenNoClientHeader() {
        // A6: when the client sends no X-Correlation-ID, CorrelationIdFilter
        // generates one and (with the fix) exposes it as the "correlationId"
        // request attribute — the error path must emit it in the body.
        MockHttpServletRequest inner = new MockHttpServletRequest();
        inner.setRequestURI("/api/bookings/1");
        inner.setAttribute("correlationId", "server-generated-trace");
        jakarta.servlet.http.HttpServletRequest request = new jakarta.servlet.http.HttpServletRequestWrapper(inner);

        var response = handler.handleIllegalState(new IllegalStateException("state conflict"), request);

        assertThat(response.getProperties().get("traceId")).isEqualTo("server-generated-trace");
    }

    /**
     * A-03: the uncaught-exception safety net moved to its own LAST-ordered
     * advice ({@code GlobalErrorFallbackHandler}) so this advice's specific
     * handlers can sit ahead of Boot's automatic one without a catch-all
     * swallowing the built-ins — the 500 contract itself is pinned in
     * {@code GlobalErrorFallbackHandlerTest}, including the three-layer
     * ordering this composition is measured on.
     */

    static class TestController {
        public void submit(String email) {
        }
    }

    // ---- L31: the unique-violation (23505) backstop → 409 CONFLICT ----

    @Test
    void handleUniqueViolation_mapsPostgresUniqueRaceToConflict() {
        // The pgjdbc contract: SQLSTATE 23505 (unique_violation) rides
        // getSQLState() of the SQLException in the cause chain.
        java.sql.SQLException pgCause = new java.sql.SQLException(
                "ERROR: duplicate key value violates unique constraint"
                        + " \"uq_property_details_listing_id\"", "23505");
        org.springframework.dao.DataIntegrityViolationException ex =
                new org.springframework.dao.DataIntegrityViolationException(
                        "could not execute statement", pgCause);

        HttpServletRequest request = new StubHttpServletRequest("/api/v1/listings/1/property");
        var response = handler.handleUniqueViolation(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getType()).isEqualTo(URI.create("https://marketplace.com/errors/conflict"));
        assertThat(response.getProperties().get("errorCode")).isEqualTo("CONFLICT-001");
    }

    @Test
    void handleUniqueViolation_otherIntegrityViolationsStayInternal() {
        java.sql.SQLException pgCause = new java.sql.SQLException(
                "ERROR: null value in column \"rooms\" violates not-null constraint", "23502");
        org.springframework.dao.DataIntegrityViolationException ex =
                new org.springframework.dao.DataIntegrityViolationException(
                        "could not execute statement", pgCause);

        HttpServletRequest request = new StubHttpServletRequest("/api/v1/listings/1/property");
        var response = handler.handleUniqueViolation(ex, request);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    // ------------------------------------------------------------------
    // A-03 (compliance plan 0.9 / matrix row 7 — the ordered composition
    // and the unified official carrier) — two structural pins.
    // ------------------------------------------------------------------

    /**
     * The official doc's prescription, verbatim (mvc-ann-rest-exceptions):
     * "you'll need to ensure your handler is ordered ahead of the one
     * configured by Spring Boot whose order is 0" — the house advice carries
     * the highest precedence so its two documented built-in takeovers (the
     * fieldErrors validation contract, the taxonomy 404) win over Boot's
     * autoconfigured {@code ProblemDetailsExceptionHandler}, while every
     * other built-in falls through to the official automatic rendering.
     */
    @Test
    void adviceIsOrderedAheadOfTheBootAutomaticProblemDetailsHandler_a03() {
        Order order = GlobalExceptionHandler.class.getAnnotation(Order.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    /**
     * The unified carrier: the house API exception family rides the
     * Framework's own {@code ErrorResponseException} base (the official doc:
     * "basic ErrorResponse implementation that others can use as a convenient
     * base class"), so every domain 404/400/409/429/503 is natively
     * renderable by the Framework's own advice entry point for the type —
     * while the message contract stays the bare domain detail sentence the
     * GraphQL envelope and the logs have always carried.
     */
    @Test
    void apiProblemDetailExceptionsRideTheOfficialErrorResponseExceptionBase_a03() {
        assertThat(ErrorResponseException.class)
                .isAssignableFrom(ApiProblemDetailException.class);
        assertThat(ErrorResponseException.class)
                .isAssignableFrom(ResourceNotFoundException.class);

        UUID id = UUID.randomUUID();
        ResourceNotFoundException ex = new ResourceNotFoundException("Booking", id);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The house message contract, preserved over the official base's
        // "status, ProblemDetail[...]" rendering: the message IS the detail.
        assertThat(ex.getMessage()).isEqualTo("Booking not found: " + id);
        assertThat(ex.getMessage()).isEqualTo(ex.getBody().getDetail());
        assertThat(ex.getBody().getType()).isEqualTo(URI.create("https://marketplace.com/errors/not-found"));
        assertThat(ex.getBody().getProperties()).containsEntry("errorCode", "NF-001");
    }
}
