package com.marketplace.app.graphql;

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
import org.springframework.data.domain.OffsetScrollPosition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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
 */
@DgsComponent
public class ServiceDataFetcher {

    /**
     * Deterministic order for the services connection — the house
     * newest-first + stable tie-breaker precedent (ListingFavoritesController:
     * {@code createdAt DESC, id DESC}). Offset pagination without a total
     * order is undefined across pages; this order makes every page stable
     * (design §3.4: "Preserve deterministic order with a stable
     * tie-breaker").
     */
    private static final Sort SERVICES_ORDER =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

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
    @DgsQuery
    public ServiceResponse service(@InputArgument UUID id) {
        return serviceMapper.toResponse(catalogSpi.getActiveById(id));
    }

    /**
     * The bounded, paginated services collection — the Phase 1 (P0) closure
     * of the design plan's first material gap: this field previously
     * resolved {@code Pageable.unpaged()}, an unbounded database read.
     *
     * <p><b>The official pagination path end to end under the DGS
     * programming model (all framework parts verified against the v12.1.0
     * integration source):</b> the SDL declares the relay arguments
     * {@code first}/{@code after}; DGS binds them onto this method's
     * {@code @InputArgument} parameters (the Spring
     * {@code ScrollSubrangeMethodArgumentResolver} is annotated-controller
     * machinery — dgs-framework has zero references to it, so the fetcher
     * binds the raw arguments itself and decodes the cursor through the
     * injected Boot cursor strategy); this method converts them into a
     * bounded Spring Data {@link Pageable}; the returned {@link Page} is
     * adapted into the {@code ServiceConnection} envelope by Boot's
     * {@code ConnectionFieldTypeVisitor} — which the DGS integration
     * applies to the DGS-registered fetcher because
     * {@code DgsSpringGraphQLAutoConfiguration#graphQlSource} runs every
     * {@code GraphQlSourceBuilderCustomizer}, including Boot's
     * {@code cursorStrategyCustomizer} — decorating this fetcher with the
     * same {@code SliceConnectionAdapter} ({@code Page} is-a
     * {@code Slice}) that produced the envelope before the migration. The
     * Connection/Edge/PageInfo types themselves are generated into the
     * DGS type registry by the integration's
     * {@code DgsTypeDefinitionConfigurerBridge} applying the same
     * {@code ConnectionTypeDefinitionConfigurer} Boot uses — identical
     * schema types, identical envelope, identical base64
     * {@code O_<offset>} cursors on the wire.</p>
     *
     * <p><b>Bounds (design §6):</b> {@code first} resolves against the
     * configured default when absent and is clamped to the configured hard
     * maximum — a client cannot widen the read. A non-positive {@code first}
     * is the relay-spec "empty page" case and answers an honestly empty
     * connection without touching the repository. The page index is derived
     * from the cursor's offset: an aligned cursor (the only kind this
     * surface emits — the adapter computes every cursor as
     * {@code number * size + index}, the measured formula of
     * spring-graphql 2.0.5's {@code SliceConnectionAdapter#cursorAt}, so
     * its {@code O_<offset>} cursors are page-aligned by construction)
     * lands exactly; a misaligned cursor — one whose {@code offset + 1} is
     * not a multiple of the requested page size, which arises when a
     * client changes {@code first} between pages — is rejected as a
     * validation error (VAL-001) rather than clamped: clamping to the
     * page boundary repeats or skips items (the measured example: page 1
     * with {@code first: 3} ends at {@code O_2}; a follow-up with
     * {@code first: 4} would replay items 0–3), the exact overlap/gap
     * ambiguity the connection contract forbids — and the framework's
     * page-number cursor math has no representation for an unaligned
     * start position in the first place.</p>
     *
     * <p><b>Cursor validation (design §6: "validate … cursor/page
     * arguments"):</b> this field paginates by offset cursors only — a
     * keyset ({@code K_}) cursor is a foreign format for this contract and
     * is rejected as a validation error (VAL-001), and an offset beyond the
     * addressable page space likewise. An undecodable cursor string is
     * rejected by the strategy itself ({@code IllegalArgumentException}
     * from the base64 or {@code O_}/{@code K_} prefix decoding — measured
     * in spring-graphql 2.0.5's {@code ScrollPositionCursorStrategy}), the
     * same VALIDATION taxonomy the resolver maps it to.</p>
     *
     * <p>Rate-limited at the house named-instance model (resilience4j
     * {@code graphqlServiceQuery}, same as every other high-impact
     * endpoint; per-subject caps are a later calibration decision per the
     * design's G-R6 model).</p>
     */
    @DgsQuery
    @RateLimiter(name = "graphqlServiceQuery")
    public Page<ServiceResponse> services(@InputArgument Integer first,
                                          @InputArgument String after) {
        int requested = (first == null)
                ? properties.pagination().defaultPageSize()
                : first;
        if (requested <= 0) {
            // Relay connection spec: a non-positive `first` asks for an
            // empty page — answer it honestly without database work.
            return Page.empty();
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        int pageNumber = pageFor(after, pageSize);
        Pageable pageable = PageRequest.of(pageNumber, pageSize, SERVICES_ORDER);
        return catalogSpi.findAll(pageable).map(serviceMapper::toResponse);
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
    @DgsMutation
    @RateLimiter(name = "graphqlCreateService")
    public ServiceResponse createService(@InputArgument ServiceInput input) {
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
     * Derives the zero-based page index from the {@code after} cursor
     * string. The exclusive-after semantics of the connection contract:
     * the item at {@code offset} is excluded, so the page starts at
     * {@code offset + 1}; page-aligned cursors (every cursor this surface
     * emits) land exactly, and a misaligned one is rejected as a
     * client-contract violation — see {@link #pageIndex} for the measured
     * framework rationale. An absent {@code after} is the first page.
     */
    private int pageFor(String after, int pageSize) {
        if (after == null) {
            return 0;
        }
        ScrollPosition position = cursorStrategy.fromCursor(after);
        return pageIndex(OffsetPosition.pageOf(position), pageSize);
    }

    /**
     * Converts a cursor offset into the zero-based page index under the
     * connection's exclusive-after semantics (the item at {@code offset} is
     * excluded, so the page starts at {@code offset + 1}). Two cases have no
     * honest page representation and are rejected as client-contract
     * violations (VAL-001): an offset beyond the addressable page space, and
     * a misaligned cursor — {@link #services} documents why alignment is part
     * of this field's contract and why rejection beats the overlap/gap
     * ambiguity of clamping.
     */
    private static int pageIndex(long offset, int pageSize) {
        long firstItem = offset + 1;
        if (firstItem > Integer.MAX_VALUE - pageSize) {
            // An offset beyond the addressable page space is a tampered
            // cursor — rejected as a client-contract violation, never an
            // overflow.
            throw new IllegalArgumentException(
                    "after cursor offset " + offset + " is outside the paginable range");
        }
        if (firstItem % pageSize != 0) {
            // Alignment is part of this field's cursor contract: the
            // framework's SliceConnectionAdapter computes every cursor as
            // number * size + index (spring-graphql 2.0.5 sources), so the
            // O_<offset> cursors this surface emits are page-aligned by
            // construction. A misaligned cursor arises only when a client
            // changes `first` between pages; clamping it to the boundary
            // (the previous behavior) repeats or skips items — the exact
            // overlap/gap ambiguity the no-overlap/no-gaps guarantee
            // forbids — so it is rejected as a validation error instead.
            throw new IllegalArgumentException(
                    "after cursor offset " + offset + " is not aligned to the requested page size "
                            + pageSize + " — paginate with the same first, or start over without after");
        }
        return (int) (firstItem / pageSize);
    }

    /**
     * The offset a connection cursor carries — strictly the forward
     * {@code O_<offset>} shape this surface's adapter emits. A keyset
     * ({@code K_}) cursor is a foreign format for this field's contract
     * and is rejected as a validation error.
     */
    private static final class OffsetPosition {
        /**
         * Extracts the offset an {@code O_<offset>} connection cursor carries.
         * Any other position shape — a keyset {@code K_} cursor — is foreign
         * to this field's contract and rejected as a validation error.
         */
        static long pageOf(ScrollPosition position) {
            if (position instanceof OffsetScrollPosition offsetPosition) {
                return offsetPosition.getOffset();
            }
            throw new IllegalArgumentException(
                    "this field paginates by offset cursors; a keyset cursor is not valid here");
        }
    }
}
