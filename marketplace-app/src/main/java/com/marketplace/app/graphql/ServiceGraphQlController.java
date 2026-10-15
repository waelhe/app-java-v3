package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.data.domain.OffsetScrollPosition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.query.ScrollSubrange;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.UUID;

/**
 * The GraphQL surface's catalog composition — the journey-aware
 * read/composition role the GraphQL target design assigns this API
 * (design §1: compose experiences, never mirror the database or duplicate
 * business rules). Every field delegates to the catalog module's declared
 * SPI; no JPA repository or entity crosses the module boundary.
 */
@Controller
public class ServiceGraphQlController {

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
     */
    public ServiceGraphQlController(CatalogSpi catalogSpi,
                                    CurrentUserProvider currentUserProvider,
                                    ServiceMapper serviceMapper,
                                    GraphQlProperties properties) {
        this.catalogSpi = catalogSpi;
        this.currentUserProvider = currentUserProvider;
        this.serviceMapper = serviceMapper;
        this.properties = properties;
    }

    /**
     * The single-service read: delegates to the catalog SPI's active-by-id
     * lookup and maps the shared view into the response contract. A missing
     * id surfaces as the SPI's not-found domain error, which the exception
     * resolver classifies to the canonical NF-001/RESOURCE pair.
     */
    @QueryMapping
    public ServiceResponse service(@Argument UUID id) {
        return serviceMapper.toResponse(catalogSpi.getActiveById(id));
    }

    /**
     * The bounded, paginated services collection — the Phase 1 (P0) closure
     * of the design plan's first material gap: this field previously
     * resolved {@code Pageable.unpaged()}, an unbounded database read.
     *
     * <p><b>The official pagination path end to end (all framework parts
     * auto-registered by the Boot starter, measured in
     * spring-boot-graphql 4.1.1):</b> the SDL declares the relay arguments
     * {@code first}/{@code after}; the auto-registered
     * {@code ScrollSubrangeMethodArgumentResolver} binds them into a
     * {@code ScrollSubrange} (the {@code after} cursor decoded by
     * the Boot-registered base64 {@code ScrollPositionCursorStrategy}); this
     * method converts the subrange into a bounded Spring Data
     * {@link Pageable}; the returned {@link Page} is adapted into the
     * {@code ServiceConnection} envelope by the auto-registered
     * {@code SliceConnectionAdapter} — which also produces the next page's
     * {@code O_<offset>} cursors, so the cursor format stays the
     * framework's own, never a hand-rolled string.</p>
     *
     * <p><b>The parameter carries no {@code @Argument} — a measured
     * contract of the resolver chain (spring-graphql 2.0.5 sources):
     * {@code AnnotatedControllerConfigurer} registers
     * {@code ArgumentMethodArgumentResolver} ahead of the subrange
     * resolver, so an {@code @Argument}-annotated parameter is claimed by
     * name binding against a schema argument "subrange" that does not
     * exist. The binder returns {@code null} for an omitted argument, the
     * method fails internally, and the startup schema inspection reports
     * the parameter as an unmapped argument. The exact
     * {@code ScrollSubrange} type is what
     * {@code ScrollSubrangeMethodArgumentResolver.supportsParameter}
     * claims — the binding contract of this surface.</p>
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
     * addressable page space likewise.</p>
     *
     * <p>Rate-limited at the house named-instance model (resilience4j
     * {@code graphqlServiceQuery}, same as every other high-impact
     * endpoint; per-subject caps are a later calibration decision per the
     * design's G-R6 model).</p>
     */
    @QueryMapping
    @RateLimiter(name = "graphqlServiceQuery")
    public Page<ServiceResponse> services(ScrollSubrange subrange) {
        int requested = subrange.count().orElse(properties.pagination().defaultPageSize());
        if (requested <= 0) {
            // Relay connection spec: a non-positive `first` asks for an
            // empty page — answer it honestly without database work.
            return Page.empty();
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        int pageNumber = pageFor(subrange, pageSize);
        Pageable pageable = PageRequest.of(pageNumber, pageSize, SERVICES_ORDER);
        return catalogSpi.findAll(pageable).map(serviceMapper::toResponse);
    }

    /**
     * The create command: the framework binds and validates the input
     * ({@code @Argument} + {@code @Valid}), the caller is resolved to the
     * owning provider, and the write delegates to the catalog SPI's single
     * authoritative use case — the same business rule the REST surface
     * invokes. Currency keeps the pre-B4 SAR house default. Rate-limited at
     * the mutation budget ({@code graphqlCreateService}).
     */
    @MutationMapping
    @RateLimiter(name = "graphqlCreateService")
    public ServiceResponse createService(@Argument @Valid ServiceInput input,
                                         Authentication authentication) {
        UUID providerId = currentUserProvider.getCurrentUserId(authentication);
        // GraphQL surface keeps the pre-B4 money contract: SAR house default
        // (the currency parameter is the REST catalog surface's extension).
        ProviderListingView listing = catalogSpi.create(
                providerId, input.name(), input.description(),
                input.category(), input.priceCents(), null);
        return serviceMapper.toResponse(listing);
    }

    /**
     * Derives the zero-based page index from the subrange's cursor
     * position. The exclusive-after semantics of the connection contract:
     * the item at {@code offset} is excluded, so the page starts at
     * {@code offset + 1}; page-aligned cursors (every cursor this surface
     * emits) land exactly, and a misaligned one is rejected as a
     * client-contract violation — see {@link #pageIndex} for the measured
     * framework rationale.
     */
    private int pageFor(ScrollSubrange subrange, int pageSize) {
        return subrange.position()
                .map(OffsetPosition::pageOf)
                .map(offset -> pageIndex(offset, pageSize))
                .orElse(0);
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
