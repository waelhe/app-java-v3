package com.marketplace.shared.api;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    {
        when(request.getRequestURI()).thenReturn("/api/test");
    }

    @Test
    void handleValidationReturns400() {
        var ex = mock(MethodArgumentNotValidException.class);
        var bindingResult = mock(org.springframework.validation.BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(java.util.List.of());
        var pd = handler.handleValidation(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST.value(), pd.getStatus());
        assertEquals(URI.create("https://marketplace.com/errors/validation"), pd.getType());
    }

    @Test
    void handleConstraintViolationReturns400() {
        var ex = new jakarta.validation.ConstraintViolationException("violation", null);
        var pd = handler.handleConstraintViolation(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST.value(), pd.getStatus());
        assertEquals(URI.create("https://marketplace.com/errors/constraint-violation"), pd.getType());
    }

    @Test
    void handleOptimisticLockReturns409() {
        var ex = mock(ObjectOptimisticLockingFailureException.class);
        var pd = handler.handleOptimisticLock(ex, request);

        assertEquals(HttpStatus.CONFLICT.value(), pd.getStatus());
        assertEquals(URI.create("https://marketplace.com/errors/optimistic-lock"), pd.getType());
    }

    @Test
    void handleAccessDeniedReturns403() {
        var ex = new AccessDeniedException("denied");
        var pd = handler.handleAccessDenied(ex, request);

        assertEquals(HttpStatus.FORBIDDEN.value(), pd.getStatus());
        assertEquals(URI.create("https://marketplace.com/errors/access-denied"), pd.getType());
    }

    @Test
    void handleAuthenticationReturns401() {
        var ex = new BadCredentialsException("bad credentials");
        var pd = handler.handleAuthentication(ex, request);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), pd.getStatus());
        assertEquals(URI.create("https://marketplace.com/errors/unauthorized"), pd.getType());
    }

    @Test
    void handleResourceNotFoundReturns404() {
        var ex = new ResourceNotFoundException("Booking not found");
        var pd = handler.handleResourceNotFound(ex, request);

        assertEquals(HttpStatus.NOT_FOUND.value(), pd.getStatus());
        assertEquals(URI.create("https://marketplace.com/errors/not-found"), pd.getType());
        assertEquals("Booking not found", pd.getDetail());
    }

    @Test
    void handleNoResourceReturns404() {
        var ex = new NoResourceFoundException(null, "GET", "/api/test");
        var pd = handler.handleNoResource(ex, request);

        assertEquals(HttpStatus.NOT_FOUND.value(), pd.getStatus());
        assertEquals(URI.create("https://marketplace.com/errors/not-found"), pd.getType());
    }

    @Test
    void handleRateLimitedReturns429() {
        var ex = mock(RequestNotPermitted.class);
        var pd = handler.handleRateLimited(ex, request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), pd.getStatus());
        assertEquals(URI.create("https://marketplace.com/errors/rate-limited"), pd.getType());
    }

    @Test
    void handleCircuitBreakerOpenReturns503() {
        var ex = mock(CallNotPermittedException.class);
        var pd = handler.handleCircuitBreakerOpen(ex, request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), pd.getStatus());
        assertEquals(URI.create("https://marketplace.com/errors/circuit-breaker-open"), pd.getType());
    }

    @Test
    void handleIllegalStateReturns409() {
        var ex = new IllegalStateException("Invalid state");
        var pd = handler.handleIllegalState(ex, request);

        assertEquals(HttpStatus.CONFLICT.value(), pd.getStatus());
        assertEquals(URI.create("https://marketplace.com/errors/conflict"), pd.getType());
    }

    @Test
    void handleIllegalArgumentReturns400() {
        var ex = new IllegalArgumentException("Invalid argument");
        var pd = handler.handleIllegalArgument(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST.value(), pd.getStatus());
        assertEquals(URI.create("https://marketplace.com/errors/bad-request"), pd.getType());
    }

    @Test
    void handleGeneralReturns500() {
        var ex = new RuntimeException("Unexpected error");
        var pd = handler.handleGeneral(ex, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), pd.getStatus());
        assertEquals(URI.create("https://marketplace.com/errors/internal-error"), pd.getType());
    }
}
