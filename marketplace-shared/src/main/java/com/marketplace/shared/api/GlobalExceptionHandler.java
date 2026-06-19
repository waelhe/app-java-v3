package com.marketplace.shared.api;

import java.net.URI;
import java.util.List;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global REST exception handler using RFC 7807 {@link ProblemDetail}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String CORRELATION_ID_ATTRIBUTE = "correlationId";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail pd = problem(ApiErrorTaxonomy.VALIDATION, "Validation failed", request, null);
        List<ApiErrorPayload.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiErrorPayload.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        pd.setProperty("fieldErrors", fieldErrors);
        return pd;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.VALIDATION, "Constraint violation", request, null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.CONFLICT, "Resource was modified by another transaction. Please retry.", request, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.AUTHZ, "Access denied", request, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.AUTHN, "Authentication required", request, null);
    }

    @ExceptionHandler({ResourceNotFoundException.class, BadRequestException.class, ConflictException.class})
    public ProblemDetail handleApiProblemDetail(ApiProblemDetailException ex, HttpServletRequest request) {
        ProblemDetail pd = ex.getBody();
        pd.setInstance(URI.create(request.getRequestURI()));
        return pd;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.NOT_FOUND, "Resource not found", request, null);
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ProblemDetail handleRateLimited(RequestNotPermitted ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.RATE_LIMIT, "Rate limit exceeded. Please try again later.", request, null);
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ProblemDetail handleCircuitBreakerOpen(CallNotPermittedException ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.SERVICE_UNAVAILABLE,
                "Service temporarily unavailable. Please try again later.", request, "Service currently degraded");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        // Map the ResponseStatusException's status code to the closest ApiErrorTaxonomy.
        // This prevents the catch-all @ExceptionHandler(Exception.class) from returning 500.
        // Reference: Spring Framework Reference — Web MVC Exception Handling;
        // https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html
        HttpStatusCode statusCode = ex.getStatusCode();
        ApiErrorTaxonomy taxonomy;
        String detail = ex.getReason() != null ? ex.getReason() : statusCode.toString();

        if (statusCode == HttpStatus.TOO_MANY_REQUESTS) {
            taxonomy = ApiErrorTaxonomy.RATE_LIMIT;
        } else if (statusCode == HttpStatus.NOT_FOUND) {
            taxonomy = ApiErrorTaxonomy.NOT_FOUND;
        } else if (statusCode == HttpStatus.CONFLICT) {
            taxonomy = ApiErrorTaxonomy.CONFLICT;
        } else if (statusCode == HttpStatus.SERVICE_UNAVAILABLE) {
            taxonomy = ApiErrorTaxonomy.SERVICE_UNAVAILABLE;
        } else if (statusCode.is4xxClientError()) {
            taxonomy = ApiErrorTaxonomy.VALIDATION;
        } else {
            taxonomy = ApiErrorTaxonomy.INTERNAL;
        }

        ProblemDetail pd = problem(taxonomy, detail, request, null);
        pd.setStatus(statusCode.value());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return problem(ApiErrorTaxonomy.INTERNAL, "An unexpected error occurred", request, null);
    }

    private ProblemDetail problem(ApiErrorTaxonomy taxonomy, String detail, HttpServletRequest request, String userMessage) {
        String traceId = request.getHeader(CORRELATION_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            Object correlationIdAttribute = request.getAttribute(CORRELATION_ID_HEADER);
            if (!(correlationIdAttribute instanceof String correlationId) || correlationId.isBlank()) {
                correlationIdAttribute = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
            }
            if (correlationIdAttribute instanceof String correlationId && !correlationId.isBlank()) {
                traceId = correlationId;
            }
        }

        return ApiProblemDetails.fromTaxonomy(taxonomy, detail, request.getRequestURI(), userMessage, traceId);
    }
}
