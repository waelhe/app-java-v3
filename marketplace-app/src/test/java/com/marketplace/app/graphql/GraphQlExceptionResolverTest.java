package com.marketplace.app.graphql;

import com.marketplace.shared.api.ApiErrorTaxonomy;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.api.TooManyRequestsException;
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
            new GraphQlProperties(new GraphQlProperties.Errors(false), null, null));
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

    // ------------------------------------------------------------------
    // D1 (adversarial review 2026-11-10) — the house ApiProblemDetail
    // family rides its own branch: the taxonomy the exception CARRIES is
    // the contract, on both transports — never the masked INT-001.
    // ------------------------------------------------------------------

    /**
     * D1: a {@code BadRequestException} (the house 400 VAL-001 carrier —
     * the exact exception {@code CatalogService} and the REST advice emit)
     * must surface as VALIDATION on GraphQL, exactly as its REST twin
     * answers 400 — never INT-001.
     */
    @Test
    void mapsTheHouseBadRequestFamilyToItsCarriedValidationTaxonomy() {
        GraphQLError error = first(resolver.resolveException(
                new BadRequestException("Service slot overlaps an existing booking"), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.BAD_REQUEST);
        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.VALIDATION.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.VALIDATION.category());
        assertThat(error.getMessage()).isEqualTo("Service slot overlaps an existing booking");
    }

    /**
     * D1: a {@code ConflictException} (the house 409 CONFLICT-001 carrier —
     * the duplicate-SKU path {@code ProductVariantService} throws through
     * the DataFetcher) must surface as CONFLICT on GraphQL with the
     * BAD_REQUEST transport hint (the five-value vocabulary's closest — the
     * IllegalState precedent), exactly as its REST twin answers 409 — never
     * INT-001.
     */
    @Test
    void mapsTheHouseConflictFamilyToItsCarriedConflictTaxonomy() {
        GraphQLError error = first(resolver.resolveException(
                new ConflictException("SKU already exists for this product"), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.BAD_REQUEST);
        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.CONFLICT.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.CONFLICT.category());
        assertThat(error.getMessage()).isEqualTo("SKU already exists for this product");
    }

    /**
     * D1: a {@code ServiceUnavailableException} (the house 503 SU-001
     * carrier — "capability switched off, not broken") must surface as
     * SERVICE_UNAVAILABLE on GraphQL with the INTERNAL_ERROR transport hint
     * (no availability value exists in the five-value vocabulary), exactly
     * as its REST twin answers 503 — never the masked INT-001 with the
     * generic sentence that erases the reason.
     */
    @Test
    void mapsTheHouseServiceUnavailableFamilyToItsCarriedAvailabilityTaxonomy() {
        GraphQLError error = first(resolver.resolveException(
                new ServiceUnavailableException("Media processing is currently disabled"), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.INTERNAL_ERROR);
        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.SERVICE_UNAVAILABLE.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.SERVICE_UNAVAILABLE.category());
        assertThat(error.getMessage()).isEqualTo("Media processing is currently disabled");
    }

    /**
     * D1: a {@code TooManyRequestsException} (the house 429 RL-001 carrier)
     * must surface as RATE_LIMIT on GraphQL with the BAD_REQUEST transport
     * hint (the RequestNotPermitted precedent), exactly as its REST twin
     * answers 429 — never INT-001.
     */
    @Test
    void mapsTheHouseTooManyRequestsFamilyToItsCarriedRateLimitTaxonomy() {
        GraphQLError error = first(resolver.resolveException(
                new TooManyRequestsException("Too many export requests"), env).block());

        assertThat(error.getErrorType()).isEqualTo(org.springframework.graphql.execution.ErrorType.BAD_REQUEST);
        assertThat(error.getExtensions()).containsEntry("errorCode", ApiErrorTaxonomy.RATE_LIMIT.errorCode());
        assertThat(error.getExtensions()).containsEntry("category", ApiErrorTaxonomy.RATE_LIMIT.category());
        assertThat(error.getMessage()).isEqualTo("Too many export requests");
    }

    /**
     * Asserts the resolver produced exactly one error and returns it.
     */
    private static GraphQLError first(List<GraphQLError> errors) {
        assertThat(errors).hasSize(1);
        return errors.getFirst();
    }
}
