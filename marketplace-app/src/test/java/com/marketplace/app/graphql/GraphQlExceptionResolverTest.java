package com.marketplace.app.graphql;

import com.marketplace.shared.api.ResourceNotFoundException;
import graphql.GraphQLError;
import graphql.execution.ExecutionStepInfo;
import graphql.language.Field;
import graphql.language.SourceLocation;
import graphql.schema.DataFetchingEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A4: GraphQL must not leak authorization failures as INTERNAL_ERROR — an
 * {@link AccessDeniedException} is a 403-equivalent and must surface as
 * {@code FORBIDDEN}/{@code ACCESS_DENIED}, matching the AUTHZ taxonomy.
 */
class GraphQlExceptionResolverTest {

    private final GraphQlExceptionResolver resolver = new GraphQlExceptionResolver(false);
    private final DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);

    /**
     * Stubs the minimal {@code DataFetchingEnvironment} surface the error
     * builder reads (field source location + execution path).
     */
    @BeforeEach
    void setUpEnvironment() {
        Field field = mock(Field.class);
        when(field.getSourceLocation()).thenReturn(new SourceLocation(1, 1));
        when(env.getField()).thenReturn(field);
        ExecutionStepInfo stepInfo = mock(ExecutionStepInfo.class);
        when(stepInfo.getPath()).thenReturn(graphql.execution.ResultPath.rootPath());
        when(env.getExecutionStepInfo()).thenReturn(stepInfo);
    }

    /**
     * A4: an {@code AccessDeniedException} must surface as
     * FORBIDDEN/ACCESS_DENIED (authz), never INTERNAL_ERROR.
     */
    @Test
    void mapsAccessDeniedToForbiddenWithAccessDeniedCode() {
        GraphQLError error = first(resolver.resolveException(new AccessDeniedException("No access"), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.FORBIDDEN);
        assertThat(error.getExtensions()).containsEntry("errorCode", "ACCESS_DENIED");
        assertThat(error.getExtensions()).containsEntry("category", "authz");
        assertThat(error.getMessage()).isEqualTo("No access");
    }

    /**
     * Regression: the NOT_FOUND mapping is unchanged by the A4 branch.
     */
    @Test
    void mapsResourceNotFoundAsBefore() {
        GraphQLError error = first(resolver.resolveException(
                new ResourceNotFoundException("Listing", java.util.UUID.randomUUID()), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.NOT_FOUND);
    }

    /**
     * Regression: the DOMAIN_CONFLICT mapping is unchanged by the A4
     * branch.
     */
    @Test
    void mapsDomainConflictAsBefore() {
        GraphQLError error = first(resolver.resolveException(new IllegalStateException("boom"), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.BAD_REQUEST);
        assertThat(error.getExtensions()).containsEntry("errorCode", "DOMAIN_CONFLICT");
    }

    /**
     * Bean Validation failures (surfaced by spring-graphql's ValidationHelper
     * for @Valid @Argument parameters) must carry the same VALIDATION
     * taxonomy the REST GlobalExceptionHandler returns for
     * ConstraintViolationException — never the masked INTERNAL_ERROR
     * fallback.
     */
    @Test
    void mapsConstraintViolationToValidationTaxonomy() {
        GraphQLError error = first(resolver.resolveException(
                new jakarta.validation.ConstraintViolationException(
                        "createService.input.priceCents: must be greater than or equal to 0",
                        java.util.Set.of()),
                env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.BAD_REQUEST);
        assertThat(error.getExtensions()).containsEntry("errorCode", "VALIDATION_ERROR");
        assertThat(error.getExtensions()).containsEntry("category", "VALIDATION");
        assertThat(error.getMessage()).contains("must be greater than or equal to 0");
    }

    /**
     * Asserts the resolver produced exactly one error and returns it.
     */
    private static GraphQLError first(List<GraphQLError> errors) {
        assertThat(errors).hasSize(1);
        return errors.getFirst();
    }
}