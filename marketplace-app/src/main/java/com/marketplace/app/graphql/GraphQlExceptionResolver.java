package com.marketplace.app.graphql;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CorrelationIdFilter;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    private static final String GENERIC_INTERNAL_MESSAGE = "An unexpected error occurred";

    private final boolean includeTraceId;

    public GraphQlExceptionResolver(@Value("${marketplace.graphql.errors.include-trace-id:false}") boolean includeTraceId) {
        this.includeTraceId = includeTraceId;
    }

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof ResourceNotFoundException) {
            return buildError(env, ErrorType.NOT_FOUND, "NOT_FOUND", "RESOURCE", ex.getMessage());
        }
        if (ex instanceof IllegalArgumentException) {
            return buildError(env, ErrorType.BAD_REQUEST, "VALIDATION_ERROR", "VALIDATION", ex.getMessage());
        }
        if (ex instanceof IllegalStateException) {
            return buildError(env, ErrorType.BAD_REQUEST, "DOMAIN_CONFLICT", "DOMAIN", ex.getMessage());
        }

        return buildError(env, ErrorType.INTERNAL_ERROR, "INTERNAL_ERROR", "INTERNAL", GENERIC_INTERNAL_MESSAGE);
    }

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
