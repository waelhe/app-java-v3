package com.marketplace.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The uncaught-exception safety net of the ordered error-contract
 * composition (A-03, compliance plan 0.9): the 500 INTERNAL problem body
 * for exceptions no other advice claims.
 *
 * <p><b>Why this is a separate advice, measured:</b> a single advice
 * carrying BOTH the specific house handlers AND an {@code @ExceptionHandler
 * (Exception.class)} catch-all cannot sit ahead of Spring Boot's
 * autoconfigured {@code ProblemDetailsExceptionHandler} ({@code @Order(0)},
 * live via {@code spring.mvc.problemdetails.enabled=true}) without the
 * catch-all swallowing every built-in Spring MVC exception before the
 * automatic handler sees it — a POST on a GET-only path answered 500
 * instead of 405 (the measured regression this split removes). The
 * official reference ({@code mvc-ann-rest-exceptions.html}) prescribes the
 * takeover advice for <em>specific</em> built-ins only ("you may prefer to
 * create another {@code @ControllerAdvice} ... if you want to take over
 * the handling of a specific built-in exception. You'll need to ensure
 * your handler is ordered ahead of the one configured by Spring Boot whose
 * order is 0."), so the composition is three layers, in resolution order:
 *
 * <ol>
 *   <li>{@link GlobalExceptionHandler} — the specific house contracts
 *       (the two built-in takeovers: validation {@code fieldErrors} and
 *       the taxonomy 404; plus every domain/security/validation/resilience
 *       handler), ordered {@link Ordered#HIGHEST_PRECEDENCE};</li>
 *   <li>Spring Boot's automatic {@code ProblemDetailsExceptionHandler}
 *       ({@code @Order(0)}) — every other built-in MVC exception renders
 *       the Framework's own RFC 9457 body (405, 415, 406, ...), the
 *       official automatic first;</li>
 *   <li>this advice — {@link Ordered#LOWEST_PRECEDENCE} — the last
 *       resort: what neither layer claims is masked behind the INTERNAL
 *       taxonomy body (the house 500 contract, byte-identical to the
 *       pre-split catch-all: same rendering machinery in
 *       {@link ProblemDetailRendering}, same i18n fallbacks).</li>
 * </ol>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalErrorFallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorFallbackHandler.class);

    private final MessageSource messageSource;

    public GlobalErrorFallbackHandler() {
        this.messageSource = null;
    }

    @Autowired
    public GlobalErrorFallbackHandler(ObjectProvider<MessageSource> messageSource) {
        this.messageSource = messageSource.getIfAvailable();
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUncaught(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return ProblemDetailRendering.problem(ApiErrorTaxonomy.INTERNAL, "An unexpected error occurred",
                request, null, "detail", messageSource);
    }
}
