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

        // A null detail key suffix passes an ALREADY-localized detail through
        // unchanged: the caller resolved it itself through the args-aware
        // resolve below (the D4 type-mismatch leg inserts the rejected
        // parameter/value/type into its message, so it resolves once and the
        // problem body carries that exact text — never a second blind pass).
        String detailKey = detailKeySuffix != null
                ? KEY_PREFIX + taxonomy.errorCode() + "." + detailKeySuffix : null;
        String localizedDetail = resolve(messageSource, detailKey, detail);
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

    /**
     * Resolves a parameterized message at the request locale — the official
     * {@code MessageSource.getMessage(code, args, defaultMessage, locale)}
     * insertion mechanism (Spring Framework docs: Externalized Messages) —
     * with the same null-source/null-key fallback contract as the argless
     * resolve above. The args-aware leg exists for the detail keys whose
     * bundle entries carry {0}/{1}/{2} placeholders (the D4
     * type-mismatch-detail leg: rejected parameter, value, declared type).
     *
     * <p><b>The fallback is requested through the {@code null}-default
     * overload and merged by hand.</b> {@code AbstractMessageSource}
     * renders a NON-null default through {@code MessageFormat} when
     * arguments are present (Framework 7.0.10 — the version this build
     * pins via spring-boot-starter-parent 4.1.2 —
     * {@code AbstractMessageSource.getMessage:145}:
     * {@code return renderDefaultMessage(defaultMessage, args, locale)}),
     * and this fallback embeds the RAW rejected value. A value such as
     * {@code '{0,number}} collapses the apostrophe quoting and turns the
     * rejected parameter's name into an active {@code number}-styled
     * element whose String argument throws
     * {@code IllegalArgumentException} — the handler would 500 instead of
     * answering VAL-001 (the 2026-11-13 full-review finding). With the
     * {@code null} default the documented contract is exact (lines
     * 142-144 of the same source): a missing key answers
     * {@code getDefaultMessage(code)} — {@code null} here, because
     * {@code useCodeAsDefaultMessage} is not enabled anywhere in this
     * codebase (grep-verified; the Boot {@code MessageSourceProperties}
     * default is {@code false}) — so the English floor below answers
     * byte-identical, never formatted. The localized text itself keeps
     * the official formatting path (the bundle's own quoting is
     * author-controlled).</p>
     */
    static String resolve(MessageSource messageSource, String code, String fallback, Object... args) {
        if (messageSource == null || code == null) {
            return fallback;
        }
        String localized = messageSource.getMessage(code, args, null, LocaleContextHolder.getLocale());
        return localized != null ? localized : fallback;
    }

    static URI typeUri(ApiErrorTaxonomy taxonomy) {
        return URI.create(taxonomy.typeUri());
    }
}
