package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.Service;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.security.CurrentUserProvider;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The GraphQL surface's catalog composition — the journey-aware
 * read/composition role the GraphQL target design assigns this API
 * (design §1: compose experiences, never mirror the database or duplicate
 * business rules). Every field delegates to the catalog module's declared
 * SPI; no JPA repository or entity crosses the module boundary.
 *
 * <p><b>Programming model (Phase 2 of the design, the DGS adoption):</b>
 * this is a DGS data fetcher — {@code @DgsComponent} +
 * {@code @DgsQuery}/{@code @DgsMutation}/{@code @InputArgument}, the
 * single GraphQL programming model the corrected plan selected. The
 * former Spring-annotation controller ({@code @Controller} +
 * {@code @QueryMapping}/{@code @MutationMapping}) was the migration
 * source, not the end state; the ArchitectureRulesTest single-model
 * guard now forbids its resolver annotations repository-wide. What DGS
 * does NOT own stays Spring's: the runtime is Spring for GraphQL through
 * the DGS starter's official integration — the DGS-built schema is handed
 * to Spring's {@code ExecutionGraphQlService}, the exception resolver and
 * instrumentations remain Spring beans wired into the DGS source
 * builder, and Boot still owns startup, auto-configuration, and the
 * property namespaces it manages.</p>
 *
 * <p><b>Schema-first field names (Phase 3 of the design, Maven-owned
 * code generation):</b> the fetcher's field bindings reference
 * {@code DgsConstants} — generated at build time by the community
 * Maven adapter of Netflix DGS CodeGen (pinned in the root pom;
 * Netflix's official CodeGen plugin is Gradle-only) directly from
 * the authoritative SDL. The annotation attribute is the
 * framework's own {@code field} — {@code @DgsQuery} and
 * {@code @DgsMutation} alias it onto {@code @DgsData.field}, and an
 * empty value resolves to the method name
 * ({@code DgsSchemaProvider}: {@code getString("field").ifEmpty
 * { method.name }}, measured from the dgs 12.1.0 source) — so the
 * registration is byte-identical to the implicit-name form it
 * replaces: the constant values ARE the schema's own field-name
 * strings. The contract this buys: renaming or removing an SDL
 * field now fails compilation HERE instead of drifting silently —
 * the adapter's field names and the schema are one source of
 * truth, not two.</p>
 *
 * <p><b>Generated transport types (Phase 3's equivalence-proven
 * increment):</b> every field on this adapter returns the
 * {@code generated.types.Service} data type the same build-time
 * generation derives from the authoritative SDL — the official docs'
 * own intended use for generated data types ("return types for your
 * datafetchers"). The hand-written ServiceResponse record was
 * replaced, not paralleled: the generated type's field set IS the
 * schema contract (the engine's documented scalar mapping — ID
 * renders as String and Float as double, measured from the
 * graphql-dgs-codegen 8.6.0 sources — with the status field typed by
 * the generated ServiceStatus enum), graphql-java's GraphQLID
 * serializes a UUID and its toString identically, and enums serialize
 * by name, so the wire contract is byte-identical — the full
 * phase-1/2 integration suite proves it unchanged. The input side
 * stays hand-owned: ServiceInput's Jakarta Validation contract cannot
 * be proven equivalent from SDL (§11-Phase-3.4), so the build's
 * typeMapping points the generator at the existing record and no
 * duplicate input type is ever generated (the engine skips mapped
 * types — measured in its 8.6.0 source).</p>
 */
@DgsComponent
public class ServiceDataFetcher {

    private final CatalogSpi catalogSpi;
    private final CurrentUserProvider currentUserProvider;
    private final ServiceMapper serviceMapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final Validator validator;

    /**
     * @param catalogSpi          the catalog module's declared SPI — the only
     *                            data path this adapter may use; no repository
     *                            or entity crosses the Modulith boundary
     * @param currentUserProvider resolves the authenticated principal into
     *                            the owning provider id for the write path
     * @param serviceMapper       maps the shared catalog view into this
     *                            surface's response contract
     * @param properties          the typed {@code marketplace.graphql.*}
     *                            binding — pagination bounds and limit
     *                            budgets, validated at startup
     * @param cursorStrategy      the connection-cursor codec — the SAME
     *                            Boot-auto-configured bean
     *                            ({@code GraphQlDataAutoConfiguration#cursorStrategy}:
     *                            {@code ScrollPositionCursorStrategy} with
     *                            base64 encoding) that produced and consumed
     *                            this surface's cursors before the DGS
     *                            migration; the cursor wire format is the
     *                            framework's own, never a hand-rolled string
     * @param validator           Jakarta validator for the mutation input's
     *                            Bean-Validation contract — DGS's argument
     *                            resolver has no {@code @Valid} support
     *                            (measured: zero {@code jakarta.validation}
     *                            usage in dgs-framework 12.1.0), so the
     *                            boundary validation is explicit and throws
     *                            the same {@link ConstraintViolationException}
     *                            the Spring {@code @Valid} path threw —
     *                            identical taxonomy mapping downstream
     */
    public ServiceDataFetcher(CatalogSpi catalogSpi,
                              CurrentUserProvider currentUserProvider,
                              ServiceMapper serviceMapper,
                              GraphQlProperties properties,
                              EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                              Validator validator) {
        this.catalogSpi = catalogSpi;
        this.currentUserProvider = currentUserProvider;
        this.serviceMapper = serviceMapper;
        this.properties = properties;
        this.cursorStrategy = cursorStrategy;
        this.validator = validator;
    }

    /**
     * The single-service read: delegates to the catalog SPI's active-by-id
     * lookup and maps the shared view into the response contract. A missing
     * id surfaces as the SPI's not-found domain error, which the exception
     * resolver classifies to the canonical NF-001/RESOURCE pair.
     *
     * <p>{@code ID} arguments arrive as Strings from graphql-java's scalar;
     * DGS's argument conversion ({@code DefaultConversionService} with
     * {@code StringToUUIDConverter}) binds them to {@link UUID} — the same
     * target type the Spring binder produced before the migration.</p>
     */
    @DgsQuery(field = DgsConstants.QUERY.Service)
    public Service service(@InputArgument UUID id) {
        return serviceMapper.toResponse(catalogSpi.getActiveById(id));
    }

    /**
     * Bounded, forward-only keyset connection over the catalog's ACTIVE
     * listing surface. The catalog owner applies the status predicate and
     * stable createdAt DESC/id DESC sort; the cursor is opaque and decoded
     * using Spring's Boot-provided ScrollPosition cursor strategy.
     */
    @DgsQuery(field = DgsConstants.QUERY.Services)
    @RateLimiter(name = "graphqlServiceQuery")
    public Window<Service> services(@InputArgument Integer first,
                                    @InputArgument String after) {
        int requested = first == null
                ? properties.pagination().defaultPageSize()
                : first;
        if (requested <= 0) {
            return Window.from(List.of(), index -> ScrollPosition.keyset(), false);
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        return catalogSpi.scrollActiveListings(keysetPosition(after), Limit.of(pageSize))
                .map(serviceMapper::toResponse);
    }

    /**
     * The create command: DGS binds and converts the input record
     * ({@code @InputArgument} — {@code DefaultInputObjectMapper} builds
     * Java records from their record components by name), the boundary
     * validation enforces the record's Bean-Validation contract, the
     * caller is resolved to the owning provider, and the write delegates
     * to the catalog SPI's single authoritative use case — the same
     * business rule the REST surface invokes. Currency keeps the pre-B4
     * SAR house default. Rate-limited at the mutation budget
     * ({@code graphqlCreateService}).
     *
     * <p><b>Validation at the boundary, explicitly:</b> the Spring
     * annotated-controller path ran the record's {@code @Valid} contract
     * through {@code ValidationHelper} and surfaced
     * {@link ConstraintViolationException}; DGS's argument resolver has no
     * {@code @Valid} hook (measured: zero jakarta.validation usage in
     * dgs-framework 12.1.0), so this method validates the bound record
     * through the injected {@link Validator} and throws the SAME exception
     * type on violation — the {@code GraphQlExceptionResolver} maps it to
     * the identical VAL-001/validation taxonomy pair the mutation
     * integration tests assert. The authoritative use case re-enforces the
     * domain invariants either way; this is the boundary leg of the
     * design's two-layer validation rule (§4: "Validate at both schema/
     * binding and domain layers").</p>
     *
     * <p><b>The principal:</b> resolved from the request thread's
     * {@link SecurityContextHolder} — the DGS method-invocation model has
     * no {@code Authentication} parameter resolver (measured: no such
     * argument resolver in dgs-framework 12.1.0), and the integration runs
     * GraphQL data fetchers synchronously on the servlet request thread
     * (async dispatch is explicitly defaulted OFF in the DGS integration
     * guide), so the security chain's populated context is the authenticated
     * principal this write path consumes — the same
     * {@code JwtAuthenticationToken} the Spring parameter received.</p>
     */
    @DgsMutation(field = DgsConstants.MUTATION.CreateService)
    @RateLimiter(name = "graphqlCreateService")
    public Service createService(@InputArgument ServiceInput input) {
        Set<ConstraintViolation<ServiceInput>> violations = validator.validate(input);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID providerId = currentUserProvider.getCurrentUserId(authentication);
        // GraphQL surface keeps the pre-B4 money contract: SAR house default
        // (the currency parameter is the REST catalog surface's extension).
        ProviderListingView listing = catalogSpi.create(
                providerId, input.name(), input.description(),
                input.category(), input.priceCents(), null);
        return serviceMapper.toResponse(listing);
    }

    /**
     * Decode only forward keyset cursors emitted by this connection. The
     * sort keys are part of the query contract and are deliberately opaque
     * to clients; foreign/offset cursors are rejected as validation errors.
     */
    private KeysetScrollPosition keysetPosition(String after) {
        if (after == null) {
            return ScrollPosition.keyset();
        }
        ScrollPosition decoded = cursorStrategy.fromCursor(after);
        if (!(decoded instanceof KeysetScrollPosition keyset)
                || !keyset.scrollsForward()
                || !keyset.getKeys().keySet().equals(Set.of("createdAt", "id"))) {
            throw new IllegalArgumentException(
                    "after must be a forward keyset cursor emitted by services");
        }
        return keyset.forward();
    }
}

