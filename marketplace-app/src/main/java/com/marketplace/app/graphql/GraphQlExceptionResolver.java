package com.marketplace.app.graphql;

import com.marketplace.shared.api.ApiErrorTaxonomy;
import com.marketplace.shared.api.ApiProblemDetailException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CorrelationIdFilter;
import org.springframework.http.HttpStatusCode;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps data-fetcher exceptions to GraphQL errors classified by the
 * repository's CANONICAL taxonomy — {@link ApiErrorTaxonomy}, the same
 * single vocabulary the REST ProblemDetail contract emits (GraphQL target
 * design §7: "a stable machine-readable extensions.errorCode; a canonical
 * extensions.category from the shared vocabulary" — the ad-hoc demo-era
 * literals were its recorded drift).
 *
 * <p>The GraphQL envelope itself stays the GraphQL spec's (data + errors[]
 * with path/locations); the taxonomy rides the {@code extensions} the way
 * RFC 9457 carries it on REST — one machine contract across both
 * transports (docs/api/error-contract.md). The GraphQL-spec {@link ErrorType}
 * is derived per the closest value of spring-graphql's fixed five-value
 * vocabulary; the authoritative machine contract is the extensions pair,
 * because GraphQL's errorType has no rate-limit or conflict value.</p>
 *
 * <p>Leakage policy (unchanged): messages of the designated-safe domain
 * exceptions pass through; every unknown failure is masked to the generic
 * internal message. Rate-limit rejections carry the same fixed sentence
 * the REST handler returns for {@link RequestNotPermitted} — the limiter's
 * own message names internal instance state and is not client-facing.</p>
 */
@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    private static final String GENERIC_INTERNAL_MESSAGE = "An unexpected error occurred";
    private static final String RATE_LIMIT_MESSAGE = "Rate limit exceeded. Please try again later.";

    private final boolean includeTraceId;

    /**
     * @param includeTraceId whether error extensions may carry the current
     *                       correlation id (MDC) — bound from
     *                       {@code marketplace.graphql.errors.include-trace-id}
     *                       via the typed {@link GraphQlProperties} binding
     *                       (default false, so trace ids never leak to
     *                       external clients by accident)
     */
    public GraphQlExceptionResolver(GraphQlProperties properties) {
        this.includeTraceId = properties.errors().includeTraceId();
    }

    /**
     * Maps one data-fetcher exception onto the canonical taxonomy pair
     * ({@code errorCode} + {@code category} from {@link ApiErrorTaxonomy}):
     * not-found, validation (argument binding and Bean Validation), domain
     * conflict, authorization, rate limit, and the masked internal default.
     * Only the designated-safe domain messages pass through; every unknown
     * failure carries the generic internal sentence — never a stack trace,
     * SQL, or provider detail.
     *
     * <p>Adversarial-review 2026-11-10 finding D1: the house
     * {@link ApiProblemDetailException} family (BadRequest / Conflict /
     * ServiceUnavailable / TooManyRequests) rides the family branch — each
     * member carries its OWN taxonomy through {@code taxonomy()} (the same
     * single vocabulary the REST {@code GlobalExceptionHandler.handleApiProblemDetail}
     * emits), so a conflict or a validation failure surfaces as
     * CONFLICT-001/VAL-001 on GraphQL exactly as it does on REST — never as
     * the masked INT-001 internal.</p>
     */
    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof ResourceNotFoundException) {
            return buildError(env, ErrorType.NOT_FOUND, ApiErrorTaxonomy.NOT_FOUND, ex.getMessage());
        }
        if (ex instanceof ApiProblemDetailException problem) {
            // The family's own branch: the taxonomy pair is authoritative
            // (the exception carries it); the GraphQL-spec ErrorType is the
            // closest value of the fixed five-value vocabulary per the
            // class's documented rule — 400/409/429 all ride BAD_REQUEST
            // (the IllegalState→CONFLICT and RequestNotPermitted precedents),
            // 5xx rides INTERNAL_ERROR (no availability value exists), and
            // the message is the documented bare domain detail sentence.
            return buildError(env, closestErrorType(problem.taxonomy().statusCode()),
                    problem.taxonomy(), ex.getMessage());
        }
        if (ex instanceof IllegalArgumentException) {
            return buildError(env, ErrorType.BAD_REQUEST, ApiErrorTaxonomy.VALIDATION, ex.getMessage());
        }
        // Bean Validation failures surfaced by spring-graphql's ValidationHelper
        // (Jakarta @Valid on @Argument parameters) — mapped to the same VALIDATION
        // taxonomy the REST GlobalExceptionHandler returns for
        // ConstraintViolationException, so a rejected input carries identical
        // error semantics on both surfaces.
        if (ex instanceof ConstraintViolationException violation) {
            return buildError(env, ErrorType.BAD_REQUEST, ApiErrorTaxonomy.VALIDATION, violation.getMessage());
        }
        if (ex instanceof IllegalStateException) {
            return buildError(env, ErrorType.BAD_REQUEST, ApiErrorTaxonomy.CONFLICT, ex.getMessage());
        }
        if (ex instanceof AccessDeniedException) {
            // A4: distinguish an authorization failure from an internal error so
            // GraphQL clients can tell a denied request from a broken server —
            // the same AUTHZ taxonomy the REST layer returns for 403.
            return buildError(env, ErrorType.FORBIDDEN, ApiErrorTaxonomy.AUTHZ, ex.getMessage());
        }
        if (ex instanceof RequestNotPermitted) {
            // The house fail-fast rate-limit model (resilience4j named
            // instances) reaches the GraphQL resolver the same way it reaches
            // the REST advice — the RL-001 taxonomy with the identical fixed
            // sentence, never the limiter's internal instance state.
            return buildError(env, ErrorType.BAD_REQUEST, ApiErrorTaxonomy.RATE_LIMIT, RATE_LIMIT_MESSAGE);
        }

        return buildError(env, ErrorType.INTERNAL_ERROR, ApiErrorTaxonomy.INTERNAL, GENERIC_INTERNAL_MESSAGE);
    }

    /**
     * Derives the closest GraphQL-spec {@link ErrorType} for a house
     * problem-detail taxonomy's HTTP status — the class's documented rule
     * (the five-value vocabulary has no conflict, rate-limit, or
     * availability value; the extensions pair is the authoritative machine
     * contract, so the ErrorType only needs the closest transport hint).
     *
     * <p>The named statuses map directly; every OTHER 4xx (422, 410, a
     * future family member's status) rides the class hint —
     * {@code BAD_REQUEST} — because a client failure is never the
     * transport's internal-error shape; everything else stays
     * {@code INTERNAL_ERROR} (review round 2, 2026-11-11).</p>
     */
    private ErrorType closestErrorType(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 400, 409, 429 -> ErrorType.BAD_REQUEST;
            case 401 -> ErrorType.UNAUTHORIZED;
            case 403 -> ErrorType.FORBIDDEN;
            case 404 -> ErrorType.NOT_FOUND;
            // Review round 2 (CodeRabbit 2026-11-11): an unlisted 4xx (422, 410,
            // ...) is a CLIENT failure — the closest five-value hint is
            // BAD_REQUEST, never INTERNAL_ERROR (the taxonomy pair stays the
            // authoritative contract either way).
            default -> statusCode.is4xxClientError()
                    ? ErrorType.BAD_REQUEST
                    : ErrorType.INTERNAL_ERROR;
        };
    }

    /**
     * Builds the classified {@link GraphQLError} with the canonical taxonomy
     * extensions ({@code errorCode} + {@code category} from
     * {@link ApiErrorTaxonomy} and, when enabled, the MDC trace id).
     */
    private GraphQLError buildError(DataFetchingEnvironment env,
                                    ErrorType errorType,
                                    ApiErrorTaxonomy taxonomy,
                                    String message) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("errorCode", taxonomy.errorCode());
        extensions.put("category", taxonomy.category());

        if (includeTraceId) {
            String traceId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (traceId != null && !traceId.isBlank()) {
                extensions.put("traceId", traceId);
            }
        }

        return GraphqlErrorBuilder.newError(env)
                .errorType(errorType)
                .message(message)
                .extensions(extensions)
                .build();
    }
}
