package com.marketplace.shared.api;

import java.net.URI;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Base exception for API errors represented as RFC 9457 {@link ProblemDetail}.
 *
 * <p>A-03 (compliance plan 0.6 / matrix row 7 — the unified official carrier):
 * this base now extends the Framework's own {@link ErrorResponseException}
 * rather than hand-implementing {@code ErrorResponse}. The official reference
 * ({@code docs.spring.io/spring-framework/reference/web/webmvc/
 * mvc-ann-rest-exceptions.html}) defines {@code ErrorResponseException} as the
 * "basic ErrorResponse implementation that others can use as a convenient
 * base class", and its Javadoc (measured from the Framework source) states
 * the exception "can be used as is, or it can be extended as a more specific
 * exception that populates the {@link ProblemDetail#setType(URI) type} or
 * {@link ProblemDetail#setDetail(String) detail} fields, or potentially adds
 * other non-standard properties" — exactly this class's shape: the taxonomy
 * {@code type} URI, the domain {@code detail} sentence, and the
 * {@code errorCode}/{@code category} non-standard properties.
 *
 * <p>What the official base buys the platform, concretely: being an
 * {@code ErrorResponseException}, every house domain exception is now natively
 * renderable by the Framework's own {@code ResponseEntityExceptionHandler}
 * entry point for the type (its class-level {@code @ExceptionHandler} list —
 * measured from the Framework source — includes
 * {@code ErrorResponseException.class}), so the RFC 9457 body renders even in
 * contexts where this advice is not present, while the house
 * {@link GlobalExceptionHandler} keeps enriching it (instance, i18n title,
 * {@code userMessage}) when it is. The wire body is byte-identical to the
 * previous hand-rolled implementation: the same {@link ProblemDetail}
 * construction runs in the same constructor.
 *
 * <p>One deliberate override: {@link #getMessage()}. The official base renders
 * {@code "404 NOT_FOUND, ProblemDetail[type=...]"}; the house's established
 * message contract is the bare domain detail sentence — measured consumers:
 * the GraphQL resolver's error envelope
 * ({@code GraphQlExceptionResolver} maps this exception's message into the
 * GraphQL {@code errors[].message} field) and the service-level logs. Keeping
 * the detail sentence keeps both byte-identical while the HTTP body rides the
 * official carrier.
 */
public abstract class ApiProblemDetailException extends ErrorResponseException {

    private final ApiErrorTaxonomy taxonomy;

    protected ApiProblemDetailException(ApiErrorTaxonomy taxonomy, String detail) {
        super(taxonomy.statusCode(), problemDetailOf(taxonomy, detail), null);
        this.taxonomy = taxonomy;
    }

    /**
     * Builds the prebuilt RFC 9457 body the official base carries: the
     * taxonomy's status/type/title plus the house's two non-standard
     * properties ({@code errorCode}, {@code category}) — byte-identical to
     * the previous hand-rolled body (the doc's "Non-Standard Fields" section:
     * "insert into the 'properties' Map of ProblemDetail").
     */
    private static ProblemDetail problemDetailOf(ApiErrorTaxonomy taxonomy, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(taxonomy.statusCode(), detail);
        body.setType(URI.create(taxonomy.typeUri()));
        body.setTitle(taxonomy.title());
        body.setProperty("errorCode", taxonomy.errorCode());
        body.setProperty("category", taxonomy.category());
        return body;
    }

    /** Taxonomy this exception was raised under — the i18n message key. */
    public ApiErrorTaxonomy taxonomy() {
        return taxonomy;
    }

    /**
     * The domain detail sentence — the message contract the GraphQL envelope
     * and the logs have always carried. See the class Javadoc for why this
     * restores the house sentence over the official base's
     * {@code "status, ProblemDetail[...]"} rendering.
     */
    @Override
    public String getMessage() {
        return getBody().getDetail();
    }
}
