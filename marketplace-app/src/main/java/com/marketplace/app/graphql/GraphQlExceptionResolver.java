package com.marketplace.app.graphql;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CorrelationIdFilter;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    private static final String GENERIC_INTERNAL_MESSAGE = "An unexpected error occurred";

    private final boolean includeTraceId;

    /**
     * @param includeTraceId whether error extensions may carry the current
     *                       correlation id (MDC) — bound from
     *                       {@code marketplace.graphql.errors.include-trace-id}
     *                       (default false, so trace ids never leak to
     *                       external clients by accident)
     */
    public GraphQlExceptionResolver(@Value("${marketplace.graphql.errors.include-trace-id:false}") boolean includeTraceId) {
        this.includeTraceId = includeTraceId;
    }

    /**
     * Maps a data-fetcher exception to a single GraphQL error classified
     * per the shared API error taxonomy — NOT_FOUND, VALIDATION_ERROR,
     * DOMAIN_CONFLICT, ACCESS_DENIED (A4) and a masked INTERNAL fallback —
     * mirroring the REST GlobalExceptionHandler mappings.
     */
    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof ResourceNotFoundException) {
            return buildError(env, ErrorType.NOT_FOUND, "NOT_FOUND", "RESOURCE", ex.getMessage());
        }
        if (ex instanceof IllegalArgumentException) {
            return buildError(env, ErrorType.BAD_REQUEST, "VALIDATION_ERROR", "VALIDATION", ex.getMessage());
        }
        // Bean Validation failures surfaced by spring-graphql's ValidationHelper
        // (Jakarta @Valid on @Argument parameters) — mapped to the same VALIDATION
        // taxonomy the REST GlobalExceptionHandler returns for
        // ConstraintViolationException, so a rejected input carries identical
        // error semantics on both surfaces.
        if (ex instanceof ConstraintViolationException violation) {
            return buildError(env, ErrorType.BAD_REQUEST, "VALIDATION_ERROR", "VALIDATION", violation.getMessage());
        }
        if (ex instanceof IllegalStateException) {
            return buildError(env, ErrorType.BAD_REQUEST, "DOMAIN_CONFLICT", "DOMAIN", ex.getMessage());
        }
        if (ex instanceof AccessDeniedException) {
            // A4: distinguish an authorization failure from an internal error so
            // GraphQL clients can tell a denied request from a broken server,
            // matching the AUTHZ taxonomy the REST layer returns for 403.
            return buildError(env, ErrorType.FORBIDDEN, "ACCESS_DENIED", "authz", ex.getMessage());
        }

        return buildError(env, ErrorType.INTERNAL_ERROR, "INTERNAL_ERROR", "INTERNAL", GENERIC_INTERNAL_MESSAGE);
    }

    /**
     * Builds the classified {@link GraphQLError} with the taxonomy
     * extensions ({@code errorCode}, {@code category} and, when enabled,
     * the MDC trace id).
     */
    private GraphQLError buildError(DataFetchingEnvironment env,
                                    ErrorType errorType,
                                    String errorCode,
                                    String category,
                                    String message) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("errorCode", errorCode);
        extensions.put("category", category);

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
