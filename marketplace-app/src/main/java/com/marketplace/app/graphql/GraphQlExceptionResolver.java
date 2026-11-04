package com.marketplace.app.graphql;

import com.marketplace.shared.api.ApiErrorTaxonomy;
import com.marketplace.shared.api.GlobalExceptionHandler;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CorrelationIdFilter;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
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
    private static final String AUTHENTICATION_REQUIRED_MESSAGE = "Authentication required";
    private static final String CONFLICT_MESSAGE = "The request conflicts with the current state of the resource";

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
     * conflict (including the concurrent-write uniqueness races the shared
     * backstop indexes raise), authentication, authorization, rate limit,
     * service unavailability (the classified 503 family), and the masked
     * internal default.
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
        // CAT-02 (the 2026-11-03 adversarial scan, P2): a lost uniqueness
        // race — the second INSERT of a concurrent pair hits the unique
        // index the precheck could not see — used to surface as the
        // masked INTERNAL-001 envelope. The repository's own REST advice
        // classifies the SAME exception by the same split
        // (GlobalExceptionHandler: SQLSTATE 23505 → 409 CONFLICT, every
        // OTHER integrity violation stays the masked INTERNAL), and the
        // owning services' javadocs name the unique partial indexes "the
        // concurrent-write backstop": the GraphQL surface answers the
        // SAME classified conflict through the SAME one classifier
        // (never a second copy that drifts), with the same fixed
        // sentence — Spring's own message carries constraint and SQL
        // detail that never crosses the wire.
        if (ex instanceof DataIntegrityViolationException violation) {
            if (GlobalExceptionHandler.isUniqueViolation(violation)) {
                return buildError(env, ErrorType.BAD_REQUEST, ApiErrorTaxonomy.CONFLICT,
                        CONFLICT_MESSAGE);
            }
            return buildError(env, ErrorType.INTERNAL_ERROR, ApiErrorTaxonomy.INTERNAL,
                    GENERIC_INTERNAL_MESSAGE);
        }
        if (ex instanceof AccessDeniedException) {
            // A4: distinguish an authorization failure from an internal error so
            // GraphQL clients can tell a denied request from a broken server —
            // the same AUTHZ taxonomy the REST layer returns for 403.
            return buildError(env, ErrorType.FORBIDDEN, ApiErrorTaxonomy.AUTHZ, ex.getMessage());
        }
        // AUTH-01 (the 2026-11-03 adversarial scan, P1): an authentication
        // failure reaching the resolver from any OTHER path classifies as
        // AUTHN — the same taxonomy and the same fixed safe sentence the
        // REST advice returns for AuthenticationException — never the
        // masked INTERNAL-001. (The token surface's own Saleor payload
        // contract — INVALID_CREDENTIALS in the CreateToken/RefreshToken
        // payload — is answered inside TokenDataFetcher and never reaches
        // this branch; Spring Security's own exception messages stay
        // server-side.)
        // The 2026-11-04 adversarial delta review: the INFRA family —
        // AuthenticationServiceException and its InternalAuthenticationServiceException
        // subclass (a DB outage inside the provider's load leg) — is guarded
        // OUT of this classification: it is a broken server, not a caller who
        // must authenticate, so it skips to the masked INTERNAL branch below
        // (never classified as "Authentication required").
        if (ex instanceof AuthenticationException authentication
                && !(authentication instanceof AuthenticationServiceException)) {
            return buildError(env, ErrorType.UNAUTHORIZED, ApiErrorTaxonomy.AUTHN,
                    AUTHENTICATION_REQUIRED_MESSAGE);
        }
        if (ex instanceof RequestNotPermitted) {
            // The house fail-fast rate-limit model (resilience4j named
            // instances) reaches the GraphQL resolver the same way it reaches
            // the REST advice — the RL-001 taxonomy with the identical fixed
            // sentence, never the limiter's internal instance state.
            return buildError(env, ErrorType.BAD_REQUEST, ApiErrorTaxonomy.RATE_LIMIT, RATE_LIMIT_MESSAGE);
        }
        // ORD-02's residual (the 2026-11-04 scan) — and with it the
        // pseudonymization-HMAC OFF case (SubjectPseudonymizer.derive): the
        // house's CLASSIFIED 503 family used to fall through to the masked
        // INTERNAL envelope here even though the REST advice maps the same
        // exception to 503 SU-001 through the ApiProblemDetailException
        // branch (GlobalExceptionHandler.handleApiProblemDetail). The
        // GraphQL-spec ErrorType vocabulary has no 503 value — INTERNAL_ERROR
        // is the closest of its fixed five (a server-side condition, never
        // the client's request) — while the authoritative machine contract
        // carries the SU-001/availability pair, the same errorCode/category
        // the REST ProblemDetail body emits. The exception's own message is
        // a house-authored configuration/status sentence (the same detail
        // REST passes through), so it stays in the designated-safe set.
        if (ex instanceof ServiceUnavailableException) {
            return buildError(env, ErrorType.INTERNAL_ERROR, ApiErrorTaxonomy.SERVICE_UNAVAILABLE,
                    ex.getMessage());
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
