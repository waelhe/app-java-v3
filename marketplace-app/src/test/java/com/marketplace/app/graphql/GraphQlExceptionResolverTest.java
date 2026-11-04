package com.marketplace.app.graphql;

import com.marketplace.shared.api.ApiErrorTaxonomy;
import com.marketplace.shared.api.ResourceNotFoundException;
import graphql.GraphQLError;
import graphql.execution.ExecutionStepInfo;
import graphql.language.Field;
import graphql.language.SourceLocation;
import graphql.schema.DataFetchingEnvironment;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The GraphQL error contract's canonical alignment (GraphQL target design
 * Phase 1): every mapped failure carries the SAME {@code errorCode} +
 * {@code category} pair the REST ProblemDetail contract emits from
 * {@link ApiErrorTaxonomy} — one machine vocabulary across both
 * transports, replacing the demo-era ad-hoc literals (A4 kept the
 * authorization distinction; the taxonomy now supplies both values).
 */
class GraphQlExceptionResolverTest {

    private final GraphQlExceptionResolver resolver = new GraphQlExceptionResolver(
            new GraphQlProperties(new GraphQlProperties.Errors(false), null, null, null));
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
     * A4: an {@code AccessDeniedException} must surface as FORBIDDEN with
     * the canonical AUTHZ pair (AUTHZ-001 / authz) — never INTERNAL.
     */
    @Test
    void mapsAccessDeniedToForbiddenWithCanonicalAuthzTaxonomy() {
        GraphQLError error = first(resolver.resolveException(new AccessDeniedException("No access"), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.FORBIDDEN);
        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.AUTHZ.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.AUTHZ.category());
        assertThat(error.getMessage()).isEqualTo("No access");
    }

    /**
     * Regression: the NOT_FOUND mapping is unchanged in shape, now with the
     * canonical pair (NF-001 / not-found).
     */
    @Test
    void mapsResourceNotFoundAsBefore() {
        GraphQLError error = first(resolver.resolveException(
                new ResourceNotFoundException("Listing", UUID.randomUUID()), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.NOT_FOUND);
        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.NOT_FOUND.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.NOT_FOUND.category());
    }

    /**
     * Regression: the domain-conflict mapping keeps BAD_REQUEST errorType,
     * now with the canonical pair (CONFLICT-001 / conflict).
     */
    @Test
    void mapsDomainConflictAsBefore() {
        GraphQLError error = first(resolver.resolveException(new IllegalStateException("boom"), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.BAD_REQUEST);
        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.CONFLICT.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.CONFLICT.category());
    }

    /**
     * Bean Validation failures (surfaced by spring-graphql's ValidationHelper
     * for @Valid @Argument parameters) must carry the canonical VALIDATION
     * pair (VAL-001 / validation) — the same semantics the REST
     * GlobalExceptionHandler returns for ConstraintViolationException —
     * never the masked INTERNAL fallback.
     */
    @Test
    void mapsConstraintViolationToValidationTaxonomy() {
        GraphQLError error = first(resolver.resolveException(
                new jakarta.validation.ConstraintViolationException(
                        "createService.input.priceCents: must be greater than or equal to 0",
                        java.util.Set.of()),
                env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.BAD_REQUEST);
        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.VALIDATION.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.VALIDATION.category());
        assertThat(error.getMessage()).contains("must be greater than or equal to 0");
    }

    /**
     * Phase 1 "rate-limit the surface": a resilience4j {@code
     * RequestNotPermitted} from the GraphQL named instances maps to the
     * canonical RATE_LIMIT pair (RL-001 / rate-limit) with the SAME fixed
     * sentence the REST advice returns — never the limiter's internal
     * instance state.
     */
    @Test
    void mapsRequestNotPermittedToRateLimitTaxonomyWithTheFixedSentence() {
        GraphQLError error = first(resolver.resolveException(
                RequestNotPermitted.createRequestNotPermitted(
                        io.github.resilience4j.ratelimiter.RateLimiter.of(
                                "graphqlServiceQuery",
                                io.github.resilience4j.ratelimiter.RateLimiterConfig.ofDefaults())),
                env).block());

        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.RATE_LIMIT.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.RATE_LIMIT.category());
        assertThat(error.getMessage()).isEqualTo("Rate limit exceeded. Please try again later.");
    }

    /**
     * AUTH-01 (the 2026-11-03 adversarial scan, P1): an {@code
     * AuthenticationException} reaching the resolver from any non-token
     * path classifies as UNAUTHORIZED with the canonical AUTHN pair
     * (AUTHN-001 / authz) and the SAME fixed safe sentence the REST advice
     * returns — never the masked INTERNAL fallback, and never Spring
     * Security's own exception message (which can name the account state).
     */
    @Test
    void mapsAuthenticationExceptionToAuthnTaxonomyWithTheFixedSentence() {
        GraphQLError error = first(resolver.resolveException(
                new org.springframework.security.authentication
                        .AuthenticationCredentialsNotFoundException("missing"),
                env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.UNAUTHORIZED);
        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.AUTHN.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.AUTHN.category());
        assertThat(error.getMessage()).isEqualTo("Authentication required");
    }

    /**
     * The 2026-11-04 adversarial delta review: the INFRA family — an
     * {@code AuthenticationServiceException} (whose InternalAuthenticationServiceException
     * subclass is the DB-down shape the review demonstrated) — must NOT
     * classify as AUTHN ("Authentication required"): a broken server is not
     * a caller who must authenticate, so it answers the masked INTERNAL
     * pair (INT-001 / internal) like every unknown failure.
     */
    @Test
    void mapsAuthenticationServiceExceptionToMaskedInternalNotAuthn() {
        GraphQLError error = first(resolver.resolveException(
                new org.springframework.security.authentication
                        .AuthenticationServiceException("data source down"),
                env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.INTERNAL_ERROR);
        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.INTERNAL.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.INTERNAL.category());
        assertThat(error.getMessage()).isEqualTo("An unexpected error occurred");
    }

    /**
     * CAT-02 (the 2026-11-03 adversarial scan, P2): a lost uniqueness race
     * — the second INSERT of a concurrent pair hitting the unique backstop
     * index — classifies as CONFLICT (CONFLICT-001 / conflict) with the
     * fixed sentence, mirroring the REST advice's 409 for the same
     * SQLSTATE 23505 chain (the SAME one classifier,
     * GlobalExceptionHandler.isUniqueViolation); Spring's own message
     * (constraint/SQL detail) never crosses the wire.
     */
    @Test
    void mapsUniqueViolationRaceToConflictTaxonomyWithTheFixedSentence() {
        GraphQLError error = first(resolver.resolveException(
                new org.springframework.dao.DataIntegrityViolationException(
                        "could not execute statement",
                        new java.sql.SQLException(
                                "Duplicate key value violates unique constraint \"uk_cart_items\"",
                                "23505")),
                env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.BAD_REQUEST);
        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.CONFLICT.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.CONFLICT.category());
        assertThat(error.getMessage())
                .isEqualTo("The request conflicts with the current state of the resource");
    }

    /**
     * CAT-02's other half — the SAME split the REST advice applies: an
     * integrity violation that is NOT the 23505 uniqueness race (an FK
     * break, a check constraint) stays the masked INTERNAL pair — it is
     * a broken server state, not a client-retryable conflict, and the
     * driver's detail never leaks.
     */
    @Test
    void mapsNonUniqueIntegrityViolationToMaskedInternal() {
        GraphQLError error = first(resolver.resolveException(
                new org.springframework.dao.DataIntegrityViolationException(
                        "could not execute statement",
                        new java.sql.SQLException(
                                "insert or update on table \"checkout_lines\" violates foreign key constraint",
                                "23503")),
                env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.INTERNAL_ERROR);
        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.INTERNAL.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.INTERNAL.category());
        assertThat(error.getMessage()).isEqualTo("An unexpected error occurred");
    }

    /**
     * The masked fallback keeps the canonical INTERNAL pair (INT-001 /
     * internal) and the generic message.
     */
    @Test
    void masksUnknownFailuresAsInternal() {
        GraphQLError error = first(resolver.resolveException(new RuntimeException("secret"), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.INTERNAL_ERROR);
        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.INTERNAL.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.INTERNAL.category());
        assertThat(error.getMessage()).isEqualTo("An unexpected error occurred");
    }

    /**
     * Asserts the resolver produced exactly one error and returns it.
     */
    private static GraphQLError first(List<GraphQLError> errors) {
        assertThat(errors).hasSize(1);
        return errors.getFirst();
    }
}
