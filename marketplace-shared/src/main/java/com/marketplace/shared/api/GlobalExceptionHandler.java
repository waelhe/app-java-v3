package com.marketplace.shared.api;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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
 *
 * <p>i18n layer (roadmap B4 / gap G-PROD-4): when a {@link MessageSource}
 * is bound, the fixed English literals of this handler resolve through it
 * at the request's locale (the framework's {@code AcceptHeaderLocaleResolver}
 * populates {@code LocaleContextHolder}). The machine contract is unchanged
 * — {@code errorCode}, {@code category}, {@code type} stay authoritative —
 * while {@code title}, the fixed {@code detail} sentences and the new
 * {@code userMessage} property carry the localized human text. Domain
 * exceptions ({@link ApiProblemDetailException}) keep their dynamic
 * developer-facing detail and gain a localized {@code userMessage} from
 * the {@code error.<CODE>.user} key of their taxonomy.</p>
 *
 * <p>Without a bound MessageSource — or when a key has no bundle entry —
 * every message falls back to the exact pre-B4 English literal, so the
 * default path is byte-identical to the previous behavior.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String CORRELATION_ID_ATTRIBUTE = "correlationId";

    private static final String KEY_PREFIX = "error.";

    private final MessageSource messageSource;

    public GlobalExceptionHandler() {
        this.messageSource = null;
    }

    @Autowired
    public GlobalExceptionHandler(ObjectProvider<MessageSource> messageSource) {
        this.messageSource = messageSource.getIfAvailable();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail pd = problem(ApiErrorTaxonomy.VALIDATION, "Validation failed", request, null, "detail");
        List<ApiErrorPayload.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiErrorPayload.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        pd.setProperty("fieldErrors", fieldErrors);
        return pd;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.VALIDATION, "Constraint violation", request, null,
                "constraint-violation-detail");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.CONFLICT, "Resource was modified by another transaction. Please retry.",
                request, null, "detail");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.AUTHZ, "Access denied", request, null, "detail");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.AUTHN, "Authentication required", request, null, "detail");
    }

    @ExceptionHandler({ResourceNotFoundException.class, BadRequestException.class, ConflictException.class, ServiceUnavailableException.class})
    public ProblemDetail handleApiProblemDetail(ApiProblemDetailException ex, HttpServletRequest request) {
        ProblemDetail pd = ex.getBody();
        pd.setInstance(URI.create(request.getRequestURI()));
        ApiErrorTaxonomy taxonomy = ex.taxonomy();
        pd.setTitle(resolve(KEY_PREFIX + taxonomy.errorCode() + ".title", taxonomy.title()));
        if (pd.getProperties() == null || pd.getProperties().get("userMessage") == null) {
            String userMessage = resolve(KEY_PREFIX + taxonomy.errorCode() + ".user", null);
            if (userMessage != null) {
                pd.setProperty("userMessage", userMessage);
            }
        }
        return pd;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.NOT_FOUND, "Resource not found", request, null, "detail");
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ProblemDetail handleRateLimited(RequestNotPermitted ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.RATE_LIMIT, "Rate limit exceeded. Please try again later.",
                request, null, "detail");
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ProblemDetail handleCircuitBreakerOpen(CallNotPermittedException ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.SERVICE_UNAVAILABLE,
                "Service temporarily unavailable. Please try again later.", request,
                "Service currently degraded", "detail");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return problem(ApiErrorTaxonomy.INTERNAL, "An unexpected error occurred", request, null, "detail");
    }

    private ProblemDetail problem(ApiErrorTaxonomy taxonomy, String detail, HttpServletRequest request,
                                  String userMessage, String detailKeySuffix) {
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

        String localizedDetail = resolve(KEY_PREFIX + taxonomy.errorCode() + "." + detailKeySuffix, detail);
        String resolvedUserMessage = userMessage != null
                ? userMessage
                : resolve(KEY_PREFIX + taxonomy.errorCode() + ".user", null);

        ProblemDetail pd = ApiProblemDetails.fromTaxonomy(taxonomy, localizedDetail, request.getRequestURI(),
                resolvedUserMessage, traceId);
        pd.setTitle(resolve(KEY_PREFIX + taxonomy.errorCode() + ".title", taxonomy.title()));
        return pd;
    }

    /**
     * Resolves a message at the request locale; returns the exact fallback
     * literal when no MessageSource is bound or the key has no entry —
     * the pre-B4 behavior is the floor, not an approximation.
     */
    private String resolve(String code, String fallback) {
        if (messageSource == null || code == null) {
            return fallback;
        }
        // LocaleContextHolder carries the framework-resolved request locale
        // (AcceptHeaderLocaleResolver) and degrades to the JVM default when
        // no request is in flight — exactly the resolution contract the MVC
        // stack itself uses.
        return messageSource.getMessage(code, null, fallback, LocaleContextHolder.getLocale());
    }
}
