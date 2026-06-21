package com.marketplace.app.graphql;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CorrelationIdFilter;
import graphql.GraphQLError;
import graphql.Scalars;
import graphql.execution.ExecutionId;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.schema.DataFetchingEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.graphql.execution.ErrorType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for {@link GraphQlExceptionResolver}.
 *
 * <p>Verifies the four exception-to-GraphQL-error mappings and the optional
 * {@code traceId} extension. These tests cover the resolver in isolation
 * (no Spring context), which raises marketplace-app JaCoCo coverage above
 * the 0.70 threshold.
 *
 * <p>The {@link DataFetchingEnvironment} mock is stubbed to return non-null
 * values for the methods that {@code GraphqlErrorBuilder.newError(env)}
 * accesses internally ({@code getField()}, {@code getExecutionStepInfo()},
 * {@code getOperationDefinition()}, {@code getExecutionId()}).
 *
 * <p>Reference: Spring for GraphQL
 * <a href="https://docs.spring.io/spring-graphql/reference/execution-exception-handling.html">
 * Exception Handling</a> — "DataFetcherExceptionResolverAdapter ... resolveToSingleError
 * returns a single GraphQLError for a given exception."
 */
@ExtendWith(MockitoExtension.class)
class GraphQlExceptionResolverTest {

    @Mock
    private DataFetchingEnvironment env;

    @BeforeEach
    void stubEnv() {
        // GraphqlErrorBuilder.newError(env) accesses several env methods internally.
        // Use lenient() because not all tests trigger all code paths.
        lenient().when(env.getField()).thenReturn(new Field("testField"));
        lenient().when(env.getExecutionStepInfo()).thenReturn(
                ExecutionStepInfo.newExecutionStepInfo()
                        .type(Scalars.GraphQLString)
                        .path(ResultPath.rootPath())
                        .build());
        lenient().when(env.getOperationDefinition()).thenReturn(
                new OperationDefinition("query"));
        lenient().when(env.getExecutionId()).thenReturn(ExecutionId.generate());
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void resolveToSingleError_mapsResourceNotFoundExceptionToNotFound() {
        GraphQlExceptionResolver resolver = new GraphQlExceptionResolver(false);
        GraphQLError error = resolver.resolveToSingleError(
                new ResourceNotFoundException("Service not found: 123"), env);

        assertThat(error.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        assertThat(error.getMessage()).isEqualTo("Service not found: 123");
        assertThat(error.getExtensions())
                .containsEntry("errorCode", "NOT_FOUND")
                .containsEntry("category", "RESOURCE");
    }

    @Test
    void resolveToSingleError_mapsIllegalArgumentExceptionToBadRequest() {
        GraphQlExceptionResolver resolver = new GraphQlExceptionResolver(false);
        GraphQLError error = resolver.resolveToSingleError(
                new IllegalArgumentException("Invalid input"), env);

        assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(error.getMessage()).isEqualTo("Invalid input");
        assertThat(error.getExtensions())
                .containsEntry("errorCode", "VALIDATION_ERROR")
                .containsEntry("category", "VALIDATION");
    }

    @Test
    void resolveToSingleError_mapsIllegalStateExceptionToDomainConflict() {
        GraphQlExceptionResolver resolver = new GraphQlExceptionResolver(false);
        GraphQLError error = resolver.resolveToSingleError(
                new IllegalStateException("Booking conflict"), env);

        assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        assertThat(error.getMessage()).isEqualTo("Booking conflict");
        assertThat(error.getExtensions())
                .containsEntry("errorCode", "DOMAIN_CONFLICT")
                .containsEntry("category", "DOMAIN");
    }

    @Test
    void resolveToSingleError_mapsUnknownExceptionToInternalError() {
        GraphQlExceptionResolver resolver = new GraphQlExceptionResolver(false);
        GraphQLError error = resolver.resolveToSingleError(
                new RuntimeException("Unexpected"), env);

        assertThat(error.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR);
        assertThat(error.getMessage()).isEqualTo("An unexpected error occurred");
        assertThat(error.getExtensions())
                .containsEntry("errorCode", "INTERNAL_ERROR")
                .containsEntry("category", "INTERNAL");
    }

    @Test
    void resolveToSingleError_includesTraceIdWhenEnabledAndPresent() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "trace-abc-123");
        GraphQlExceptionResolver resolver = new GraphQlExceptionResolver(true);

        GraphQLError error = resolver.resolveToSingleError(
                new RuntimeException("Unexpected"), env);

        assertThat(error.getExtensions()).containsEntry("traceId", "trace-abc-123");
    }

    @Test
    void resolveToSingleError_skipsTraceIdWhenEnabledButAbsent() {
        GraphQlExceptionResolver resolver = new GraphQlExceptionResolver(true);

        GraphQLError error = resolver.resolveToSingleError(
                new RuntimeException("Unexpected"), env);

        assertThat(error.getExtensions()).doesNotContainKey("traceId");
    }

    @Test
    void resolveToSingleError_skipsBlankTraceId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "   ");
        GraphQlExceptionResolver resolver = new GraphQlExceptionResolver(true);

        GraphQLError error = resolver.resolveToSingleError(
                new RuntimeException("Unexpected"), env);

        assertThat(error.getExtensions()).doesNotContainKey("traceId");
    }
}
