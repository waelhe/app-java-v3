package com.marketplace.shared.api;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ProblemDetail;

/**
 * The single source of truth for rendering the house taxonomy into an RFC
 * 9457 {@link ProblemDetail} — extracted (A-03) so that the two advices of
 * the ordered composition share one implementation:
 * {@link GlobalExceptionHandler} (the specific handlers, ordered ahead of
 * Spring Boot's automatic problem-details advice) and
 * {@link GlobalErrorFallbackHandler} (the uncaught-exception safety net,
 * ordered after it).
 *
 * <p>Package-private by design: this is the rendering machinery of the two
 * house advices, not a surface other modules consume — the wire contract is
 * {@code docs/api/error-contract.md} and the taxonomy is
 * {@link ApiErrorTaxonomy}.</p>
 */
final class ProblemDetailRendering {

    static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    static final String CORRELATION_ID_ATTRIBUTE = "correlationId";
    static final String KEY_PREFIX = "error.";

    private ProblemDetailRendering() {
    }

    /**
     * Resolves the request's correlation id: the {@code X-Correlation-ID}
     * header first, then the two request attributes the correlation filter
     * populates (same three-step lookup the single advice performed before
     * the split — the traceId contract is unchanged).
     */
    static String traceId(HttpServletRequest request) {
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
        return traceId;
    }

    /**
     * Builds the taxonomy problem body with the i18n layer: the fixed
     * English literals resolve through the {@link MessageSource} at the
     * request's locale when one is bound; every message falls back to the
     * exact English literal otherwise — the pre-B4 behavior is the floor,
     * not an approximation.
     */
    static ProblemDetail problem(ApiErrorTaxonomy taxonomy, String detail, HttpServletRequest request,
                                 String userMessage, String detailKeySuffix, MessageSource messageSource) {
        String traceId = traceId(request);

        String localizedDetail = resolve(messageSource, KEY_PREFIX + taxonomy.errorCode() + "." + detailKeySuffix, detail);
        String resolvedUserMessage = userMessage != null
                ? userMessage
                : resolve(messageSource, KEY_PREFIX + taxonomy.errorCode() + ".user", null);

        ProblemDetail pd = ApiProblemDetails.fromTaxonomy(taxonomy, localizedDetail, request.getRequestURI(),
                resolvedUserMessage, traceId);
        pd.setTitle(resolve(messageSource, KEY_PREFIX + taxonomy.errorCode() + ".title", taxonomy.title()));
        return pd;
    }

    /**
     * Resolves a message at the request locale; returns the exact fallback
     * literal when no MessageSource is bound or the key has no entry.
     * {@code LocaleContextHolder} carries the framework-resolved request
     * locale ({@code AcceptHeaderLocaleResolver}) and degrades to the JVM
     * default when no request is in flight — exactly the resolution
     * contract the MVC stack itself uses.
     */
    static String resolve(MessageSource messageSource, String code, String fallback) {
        if (messageSource == null || code == null) {
            return fallback;
        }
        return messageSource.getMessage(code, null, fallback, LocaleContextHolder.getLocale());
    }

    static URI typeUri(ApiErrorTaxonomy taxonomy) {
        return URI.create(taxonomy.typeUri());
    }
}
