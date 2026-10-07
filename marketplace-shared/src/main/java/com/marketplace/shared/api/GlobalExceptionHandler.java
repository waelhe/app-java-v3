package com.marketplace.shared.api;

import java.net.URI;
import java.util.List;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global REST exception handler using RFC 9457 {@link ProblemDetail}.
 *
 * <p><b>A-03 (compliance plan 0.9 — the ordered composition the official doc
 * prescribes, verbatim):</b> with {@code spring.mvc.problemdetails.enabled=true}
 * Spring Boot autoconfigures its own {@code ResponseEntityExceptionHandler}
 * {@code @ControllerAdvice} — {@code ProblemDetailsExceptionHandler}, measured
 * {@code @Order(0)} in Boot's {@code WebMvcAutoConfiguration} — which handles
 * every built-in Spring MVC exception with RFC 9457 problem details. The
 * official reference ({@code mvc-ann-rest-exceptions.html}) prescribes the
 * composition for taking a built-in over: "you may prefer to create another
 * {@code @ControllerAdvice} instead of extending
 * {@code ResponseEntityExceptionHandler} if you want to take over the handling
 * of a specific built-in exception. You'll need to ensure your handler is
 * ordered ahead of the one configured by Spring Boot whose order is 0."
 * This advice carries that {@code @Order(Ordered.HIGHEST_PRECEDENCE)}.
 *
 * <p><b>The division of labor it produces (measured handler-resolution
 * semantics — advices are consulted in order, first match wins; within one
 * advice the most specific mapped type wins):</b> this advice takes over
 * exactly the built-ins whose <em>documented house contract</em> is richer
 * than the automatic body —
 * {@link MethodArgumentNotValidException} (the {@code fieldErrors} extension
 * the API error contract promises on every 400 validation answer) and
 * {@link NoResourceFoundException} (the taxonomy 404 with
 * {@code errorCode}/{@code category}/{@code instance}); every other built-in
 * (405, 415, 406, unreadable body, ...) falls through to Boot's automatic
 * handler and renders the Framework's own RFC 9457 body — the official
 * automatic first, the house's hand only where the contract demands more.
 * The uncaught-exception safety net ({@code @ExceptionHandler(Exception.class)}
 * → the 500 INTERNAL contract) lives in a SEPARATE last-ordered advice,
 * {@link GlobalErrorFallbackHandler} — measured: a catch-all inside this
 * advice would swallow the built-ins ahead of the automatic handler (a
 * 405 became a 500). Before the ordering, the automatic handler silently
 * shadowed the two house built-in handlers above (it was {@code @Order(0)};
 * an unordered advice is {@code LOWEST_PRECEDENCE}), so the documented
 * {@code fieldErrors} contract was dead code at the HTTP layer — the
 * measured 0.9 defect this unit closes with the contract tests that now
 * pin both sides of the split.
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
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
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
        ProblemDetail pd = problem(ApiErrorTaxonomy.VALIDATION, "Constraint violation", request, null,
                "constraint-violation-detail");
        // §5 contract completeness (platform-readiness audit): the body-
        // validation path (@Valid @RequestBody → MethodArgumentNotValidException)
        // already answers fieldErrors; the method-validation path
        // (@Validated parameters → ConstraintViolationException) answers the
        // SAME extension, the documented MAY made real on both legs — a client
        // renders one violations list for every 400, never a bare VAL-001.
        List<ApiErrorPayload.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(violation -> new ApiErrorPayload.FieldError(leafName(violation), violation.getMessage()))
                .toList();
        pd.setProperty("fieldErrors", fieldErrors);
        return pd;
    }

    /**
     * The most specific node of the violation's property path — the
     * parameter (method validation: {@code open.reason} → {@code reason})
     * or property (bean validation) the constraint rejected. The plain-name
     * shape matches the body-validation path's {@code fieldErrors[].field}
     * exactly, so both legs of the validation contract carry one shape.
     */
    private static String leafName(jakarta.validation.ConstraintViolation<?> violation) {
        String leaf = null;
        for (jakarta.validation.Path.Node node : violation.getPropertyPath()) {
            leaf = node.getName();
        }
        return leaf != null ? leaf : violation.getPropertyPath().toString();
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

    @ExceptionHandler({ResourceNotFoundException.class, BadRequestException.class, ConflictException.class, ServiceUnavailableException.class, TooManyRequestsException.class})
    public ProblemDetail handleApiProblemDetail(ApiProblemDetailException ex, HttpServletRequest request) {
        ProblemDetail pd = ex.getBody();
        pd.setInstance(URI.create(request.getRequestURI()));
        ApiErrorTaxonomy taxonomy = ex.taxonomy();
        pd.setTitle(ProblemDetailRendering.resolve(messageSource,
                ProblemDetailRendering.KEY_PREFIX + taxonomy.errorCode() + ".title", taxonomy.title()));
        if (pd.getProperties() == null || pd.getProperties().get("userMessage") == null) {
            String userMessage = ProblemDetailRendering.resolve(messageSource,
                    ProblemDetailRendering.KEY_PREFIX + taxonomy.errorCode() + ".user", null);
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

    /**
     * Resilience4j circuit-breaker open → 503 SERVICE_UNAVAILABLE with the
     * masked problem body (no internal state leaks).
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ProblemDetail handleCircuitBreakerOpen(CallNotPermittedException ex, HttpServletRequest request) {
        return problem(ApiErrorTaxonomy.SERVICE_UNAVAILABLE,
                "Service temporarily unavailable. Please try again later.", request,
                "Service currently degraded", "detail");
    }

    /**
     * Maps an {@link IllegalArgumentException} to the VALIDATION taxonomy
     * (400, RFC 7807) — A5: an illegal argument is a client-contract
     * violation, never a 500, mirroring the GraphQL resolver's
     * VALIDATION_ERROR mapping.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        // A5: an illegal argument is a client-contract violation (400), matching
        // the GraphQL resolver's VALIDATION_ERROR mapping — never a 500.
        return problem(ApiErrorTaxonomy.VALIDATION, ex.getMessage(), request, null, "detail");
    }

    /**
     * Maps an {@link IllegalStateException} to the CONFLICT taxonomy
     * (409, RFC 7807) — A5: an illegal state raised by a well-formed
     * request is a domain conflict, mirroring the GraphQL resolver's
     * DOMAIN_CONFLICT mapping.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        // A5: an illegal state in a well-formed request is a domain conflict (409),
        // matching the GraphQL resolver's DOMAIN_CONFLICT mapping — never a 500.
        return problem(ApiErrorTaxonomy.CONFLICT, ex.getMessage(), request, null, "detail");
    }

    /**
     * L31 (realestate systems plan): a UNIQUE-constraint race at the
     * database (official PostgreSQL SQLSTATE 23505 — unique_violation,
     * Appendix A. Error Codes) is a 409 CONFLICT, never a 500. The service
     * upsert makes duplicates impossible on the happy path; this is the
     * backstop for concurrent writers racing between find and insert (the
     * acceptance criterion "two property blocks for one listing are
     * impossible — DB constraint + 409"). Every other integrity violation
     * (NOT NULL, FK, CHECK) is a server-side anomaly and keeps falling to
     * {@link GlobalErrorFallbackHandler#handleUncaught}.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleUniqueViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        if (uniqueViolation(ex)) {
            log.warn("Unique constraint conflict for {}: {}", request.getRequestURI(), rootMessage(ex));
            return problem(ApiErrorTaxonomy.CONFLICT,
                    "The resource already exists (unique constraint conflict)", request, null, "detail");
        }
        log.error("Unhandled integrity violation for {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return problem(ApiErrorTaxonomy.INTERNAL, "An unexpected error occurred", request, null, "detail");
    }

    /**
     * Official PostgreSQL unique-violation SQLSTATE 23505 — read from
     * {@link java.sql.SQLException#getSQLState()} of any cause in the
     * chain (the pgjdbc contract: the state rides the exception, not the
     * message text), with a message fallback for wrappers that stringify
     * it instead.
     */
    private static boolean uniqueViolation(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sql
                    && "23505".equals(sql.getSQLState())) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().contains("23505")) {
                return true;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private ProblemDetail problem(ApiErrorTaxonomy taxonomy, String detail, HttpServletRequest request,
                                  String userMessage, String detailKeySuffix) {
        // A-03: the rendering machinery (traceId lookup + i18n + taxonomy
        // build) lives in ProblemDetailRendering so the fallback advice
        // shares this exact implementation — one source of truth.
        return ProblemDetailRendering.problem(taxonomy, detail, request, userMessage, detailKeySuffix, messageSource);
    }
}
