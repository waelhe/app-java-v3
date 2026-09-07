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

    @BeforeEach
    void setUpEnvironment() {
        Field field = mock(Field.class);
        when(field.getSourceLocation()).thenReturn(new SourceLocation(1, 1));
        when(env.getField()).thenReturn(field);
        ExecutionStepInfo stepInfo = mock(ExecutionStepInfo.class);
        when(stepInfo.getPath()).thenReturn(graphql.execution.ResultPath.rootPath());
        when(env.getExecutionStepInfo()).thenReturn(stepInfo);
    }

    @Test
    void mapsAccessDeniedToForbiddenWithAccessDeniedCode() {
        GraphQLError error = first(resolver.resolveException(new AccessDeniedException("No access"), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.FORBIDDEN);
        assertThat(error.getExtensions()).containsEntry("errorCode", "ACCESS_DENIED");
        assertThat(error.getExtensions()).containsEntry("category", "authz");
        assertThat(error.getMessage()).isEqualTo("No access");
    }

    @Test
    void mapsResourceNotFoundAsBefore() {
        GraphQLError error = first(resolver.resolveException(
                new ResourceNotFoundException("Listing", java.util.UUID.randomUUID()), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.NOT_FOUND);
    }

    @Test
    void mapsDomainConflictAsBefore() {
        GraphQLError error = first(resolver.resolveException(new IllegalStateException("boom"), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.BAD_REQUEST);
        assertThat(error.getExtensions()).containsEntry("errorCode", "DOMAIN_CONFLICT");
    }

    private static GraphQLError first(List<GraphQLError> errors) {
        assertThat(errors).hasSize(1);
        return errors.getFirst();
    }
}