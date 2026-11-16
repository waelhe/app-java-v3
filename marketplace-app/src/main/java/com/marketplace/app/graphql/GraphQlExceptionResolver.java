package com.marketplace.app.graphql;

import com.marketplace.shared.api.ApiErrorTaxonomy;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CorrelationIdFilter;
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
     */
    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof ResourceNotFoundException) {
            return buildError(env, ErrorType.NOT_FOUND, ApiErrorTaxonomy.NOT_FOUND, ex.getMessage());
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
