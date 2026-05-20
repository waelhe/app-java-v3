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
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global REST exception handler using RFC 7807 {@link ProblemDetail}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
        return problem(ApiErrorTaxonomy.INTERNAL,
                "Service temporarily unavailable. Please try again later.", request, "Service currently degraded");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return problem(ApiErrorTaxonomy.INTERNAL, "An unexpected error occurred", request, null);
    }

    private ProblemDetail problem(ApiErrorTaxonomy taxonomy, String detail, HttpServletRequest request, String userMessage) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(taxonomy.statusCode(), detail);
        pd.setType(URI.create(taxonomy.typeUri()));
        pd.setTitle(taxonomy.title());
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("errorCode", taxonomy.errorCode());
        pd.setProperty("category", taxonomy.category());
        if (userMessage != null && !userMessage.isBlank()) {
            pd.setProperty("userMessage", userMessage);
        }
        return pd;
    }
}
