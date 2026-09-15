package com.marketplace.catalog;

import com.marketplace.catalog.ProviderListingSpecifications;
import com.marketplace.catalog.spi.CatalogSpi;
import org.springframework.data.jpa.domain.Specification;
import com.marketplace.shared.api.CatalogSearchPort;
import org.springframework.modulith.NamedInterface;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ListingActivatedEvent;
import com.marketplace.shared.api.ListingCreatedEvent;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderListingSummary;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.api.SearchCriteria;
import com.marketplace.shared.api.BadRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderNameResolver;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.observation.annotation.Observed;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implements {@link ListingPriceProvider} so that the booking module can
 * derive price and provider from a listing synchronously.
 * See {@code ListingPriceProvider} Javadoc for the design rationale
 * (synchronous interface vs. asynchronous event).
 */
@Service
@Transactional
@NamedInterface("catalog-api")
public class CatalogService implements CatalogSearchPort, ListingPriceProvider, CatalogSpi {

    private final ProviderListingRepository listingRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ProviderNameResolver providerNameResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final ProviderLookupPort providerLookupPort;
    private final java.time.Clock clock;
    private final CatalogProperties catalogProperties;

    public CatalogService(ProviderListingRepository listingRepository,
                          CurrentUserProvider currentUserProvider,
                          ProviderNameResolver providerNameResolver,
                          ApplicationEventPublisher eventPublisher,
                          ProviderLookupPort providerLookupPort,
                          java.time.Clock clock,
                          CatalogProperties catalogProperties) {
        this.listingRepository = listingRepository;
        this.currentUserProvider = currentUserProvider;
        this.providerNameResolver = providerNameResolver;
        this.eventPublisher = eventPublisher;
        this.providerLookupPort = providerLookupPort;
        this.clock = clock;
        this.catalogProperties = catalogProperties;
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "catalog-active-v2", key = "#pageable.pageNumber + '-' + #pageable.pageSize + '-' + #pageable.sort")
    public Page<ListingSummary> listActive(Pageable pageable) {
        Page<ProviderListing> page = listingRepository.findByStatus(ListingStatus.ACTIVE, pageable);
        return toSummaryPage(page);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "catalog-by-category-v2", key = "#category + '-' + #pageable.pageNumber + '-' + #pageable.pageSize + '-' + #pageable.sort")
    public Page<ListingSummary> listByCategory(String category, Pageable pageable) {
        Page<ProviderListing> page = listingRepository.findByCategoryAndStatus(category, ListingStatus.ACTIVE, pageable);
        return toSummaryPage(page);
    }

    @Transactional(readOnly = true)
    public Page<ProviderListing> listByProvider(UUID providerId, Pageable pageable) {
        // CodeRabbit PR #299 round 1 (CWE-200): this is the PUBLIC provider
        // profile surface ("Browse one provider's active listings") — the
        // status filter is the documented contract, and it also guards the
        // L31 property embed from ever carrying non-ACTIVE listings' data.
        return listingRepository.findByProviderIdAndStatus(
                providerId, ListingStatus.ACTIVE, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ProviderListingView> findAll(Pageable pageable) {
        return listingRepository.findByStatus(ListingStatus.ACTIVE, pageable)
                .map(this::toProviderListingView);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "catalog-search-v2", key = "#query + '-' + #pageable.pageNumber + '-' + #pageable.pageSize + '-' + #pageable.sort")
    public Page<ListingSummary> searchFullText(String query, Pageable pageable) {
        Page<ProviderListing> page = listingRepository.searchFullText(query, pageable);
        if (page.isEmpty()) {
            // Typo-tolerance fallback (V34 / pg_trgm): lexical FTS found no
            // stem match — retry with word-similarity so a one-edit typo
            // ("gardn") still surfaces the intended listings ("garden").
            // An implementation detail of the catalog's search: the port
            // contract, the search module and every caller are unchanged.
            // Cached as the final result of this query either way.
            page = listingRepository.searchSimilar(query, pageable);
        }
        return toSummaryPage(page);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ListingSummary> searchByCriteria(SearchCriteria criteria, Pageable pageable) {
        Long minPrice = toMinorUnits(criteria.minPrice());
        Long maxPrice = toMinorUnits(criteria.maxPrice());
        Page<ProviderListing> page = listingRepository.searchByCriteria(
                criteria.category(), minPrice, maxPrice, criteria.guests(), pageable);
        return toSummaryPage(page);
    }

    /**
     * L27 (feature-expansion roadmap §5): the window-restricted criteria
     * search — the same branch coverage and price mapping as
     * {@link #searchByCriteria(SearchCriteria, Pageable)} (category / price
     * / browse-all are optional predicates of the same query), plus the
     * {@code provider_id IN (:providerIds)} restriction in BOTH the content
     * and the count query. Deliberately NOT cached at this level: the
     * whitelist varies per request, and the search module's
     * {@code search-results-v4} cache (criteria-keyed, window included) is
     * the caching surface for window searches.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ListingSummary> searchByCriteriaRestricted(SearchCriteria criteria, Set<UUID> providerIds, Pageable pageable) {
        Long minPrice = toMinorUnits(criteria.minPrice());
        Long maxPrice = toMinorUnits(criteria.maxPrice());
        Page<ProviderListing> page = listingRepository.searchByCriteriaRestricted(
                criteria.category(), minPrice, maxPrice, criteria.guests(), providerIds, pageable);
        return toSummaryPage(page);
    }

    /**
     * L27: the window-restricted full-text search — mirrors
     * {@link #searchFullText(String, Pageable)} (official
     * {@code websearch_to_tsquery} ranking, plus the pg_trgm
     * typo-tolerance fallback), with the
     * {@code provider_id IN (:providerIds)} restriction applied to both
     * queries and their counts.
     *
     * <p>Fallback condition (PR #256 full-review round): the fallback runs
     * only when NO full-text match exists at all
     * ({@code getTotalElements() == 0}) — an out-of-range page over real
     * matches is legitimately empty ({@code isEmpty()} is true while
     * {@code getTotalElements() > 0}) and must stay an honest empty page,
     * not be replaced by the similarity result set.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ListingSummary> searchFullTextRestricted(String query, Set<UUID> providerIds, Pageable pageable) {
        Page<ProviderListing> page = listingRepository.searchFullTextRestricted(query, providerIds, pageable);
        if (page.getTotalElements() == 0) {
            page = listingRepository.searchSimilarRestricted(query, providerIds, pageable);
        }
        return toSummaryPage(page);
    }

    @Transactional(readOnly = true)
    public Page<ListingSummary> listByCategorySummary(String category, Pageable pageable) {
        return listByCategory(category, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ListingSummary> listActiveSummary(Pageable pageable) {
        return listActive(pageable);
    }

    @Transactional(readOnly = true)
    public ProviderListing getById(UUID id) {
        return listingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Listing", id));
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderListingView getActiveById(UUID id) {
        return listingRepository.findById(id)
                .filter(listing -> listing.getStatus() == ListingStatus.ACTIVE)
                .map(this::toProviderListingView)
                .orElseThrow(() -> new ResourceNotFoundException("Listing", id));
    }

    /**
     * The booking-facing projection: exposes exactly the provider id, price
     * and currency the booking flow needs — no other listing state.
     */
    @Override
    @Transactional(readOnly = true)
    public ListingInfo getListingInfo(UUID listingId) {
        ProviderListing listing = getById(listingId);
        return new ListingInfo(listing.getProviderId(), listing.getPriceCents(), listing.getCurrency());
    }

    /**
     * L32: the criteria search restricted to the realestate module's
     * matching-id set — the property-facet flow. Backed by the official
     * Specifications (hasStatus + hasCategory + priceBetween + minGuests +
     * hasListingIdIn), so it honors the Pageable sort (the price/newest
     * whitelist mapped by the search surface) with the id tiebreak — and
     * the UNSORTED default is {@code id ASC}, byte-identical to the native
     * criteria path's ORDER BY id.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ListingSummary> searchByCriteriaRestrictedToListings(SearchCriteria criteria,
                                                                     Set<UUID> listingIds,
                                                                     Pageable pageable) {
        Page<ProviderListing> page = listingRepository.findAll(
                criteriaSpecification(criteria).and(
                        ProviderListingSpecifications.hasListingIdIn(listingIds)),
                deterministic(pageable));
        return toSummaryPage(page);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ListingSummary> searchByCriteriaFaceted(SearchCriteria criteria, Pageable pageable) {
        Page<ProviderListing> page = listingRepository.findAll(
                criteriaSpecification(criteria), deterministic(pageable));
        return toSummaryPage(page);
    }

    /** The shared optional-predicate specification of the faceted paths. */
    private static Specification<ProviderListing> criteriaSpecification(SearchCriteria criteria) {
        return ProviderListingSpecifications.hasStatus(ListingStatus.ACTIVE)
                .and(ProviderListingSpecifications.hasCategory(criteria.category()))
                .and(ProviderListingSpecifications.priceBetween(
                        toMinorUnits(criteria.minPrice()), toMinorUnits(criteria.maxPrice())))
                .and(ProviderListingSpecifications.minGuests(criteria.guests()));
    }

    /**
     * L32: the full-text search restricted to the given listing ids —
     * official websearch_to_tsquery ranking + the pg_trgm typo-tolerance
     * fallback, with the id restriction in both the content and count
     * queries. Text searches rank by relevance (documented: the facet sort
     * whitelist does not apply).
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ListingSummary> searchFullTextRestrictedToListings(String query, Set<UUID> listingIds, Pageable pageable) {
        Page<ProviderListing> page = listingRepository.searchFullTextRestrictedToListings(query, listingIds, pageable);
        if (page.getTotalElements() == 0) {
            page = listingRepository.searchSimilarRestrictedToListings(query, listingIds, pageable);
        }
        return toSummaryPage(page);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findActiveListingIds() {
        return listingRepository.findIdsByStatus(ListingStatus.ACTIVE);
    }

    /**
     * CodeRabbit PR #300 round 1: the criteria-eligible ACTIVE id set — the
     * shared criteria specification (ACTIVE + category/price/guests) through
     * the official Specifications path, mapped to ids in the realestate
     * adapter's own set-form shape (its {@code findListingIdsMatching} runs
     * the identical composition over {@code property_details}). A criteria
     * with every catalog predicate absent degenerates to the ACTIVE set —
     * the caller gates on {@code hasCatalogCriteria()} and keeps the
     * cheaper derived-query path for that case.
     */
    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findActiveListingIdsMatching(SearchCriteria criteria) {
        return listingRepository.findAll(criteriaSpecification(criteria))
                .stream().map(ProviderListing::getId).collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ListingSummary> findSummariesByIds(List<UUID> idsInOrder) {
        if (idsInOrder.isEmpty()) {
            return List.of();
        }
        // resolve only ACTIVE listings (the area-flow ids are already
        // active-restricted; this keeps the projection honest regardless)
        Map<UUID, ProviderListing> byId = listingRepository.findAllById(idsInOrder).stream()
                .filter(listing -> listing.getStatus() == ListingStatus.ACTIVE)
                .collect(Collectors.toMap(ProviderListing::getId, listing -> listing));
        // batch-resolve provider names (the toSummaryPage discipline)
        Map<UUID, String> providerNames = providerNameResolver.resolveNames(
                byId.values().stream().map(ProviderListing::getProviderId).collect(Collectors.toSet()));
        // restore the caller's order (the area-sorted page assembly)
        return idsInOrder.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(listing -> new ListingSummary(
                        listing.getId(),
                        listing.getTitle(),
                        listing.getCategory(),
                        BigDecimal.valueOf(listing.getPriceCents(), 2),
                        listing.getCurrency(),
                        providerNames.getOrDefault(listing.getProviderId(), "Unknown Provider")))
                .toList();
    }

    /**
     * L32: the deterministic effective sort — the requested sort (the
     * price/newest whitelist, already mapped by the search surface) with
     * the id ASC tiebreak; unsorted requests default to id ASC
     * (byte-identical to the native criteria path's ORDER BY id — offset
     * pagination requires a total order, "no deceptive pages").
     */
    private static Pageable deterministic(Pageable pageable) {
        Sort effective = pageable.getSort().isUnsorted()
                ? Sort.by(Sort.Direction.ASC, "id")
                : pageable.getSort().and(Sort.by(Sort.Direction.ASC, "id"));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), effective);
    }

    /** The min/max price mapping shared by the criteria paths. */
    private static Long toMinorUnits(BigDecimal price) {
        return price != null ? price.movePointRight(2).longValue() : null;
    }

    // Cache names are NAMESPACED BY SCHEMA VERSION (CodeRabbit #241): the four
    // ListingSummary caches hold JDK-serialized records — a record-component
    // change (currency was added by the B4 layer) lets a stale pre-change entry
    // deserialize with null components and serve it as a cache hit, bypassing
    // the mapping that populates the new component. Bumping the name with every
    // ListingSummary component change evicts at deploy time through the deploy
    // itself (old entries become unreachable and expire via the 1h TTL). Any
    // future change to ListingSummary MUST bump this suffix — pinned by
    // ListingSummaryCacheContractFilesTest.
    // L32: search-results bumps to -v3 with the criteria schema extension
    // (the plan's D-R6 decision — the criteria record gained six components;
    // the key generator's prefix bump keeps the key spaces disjoint AND the
    // name bump evicts at deploy time through the deploy itself).
    static final Set<String> CATALOG_CACHE_NAMES =
            Set.of("catalog-active-v2", "catalog-by-category-v2", "catalog-search-v2", "search-results-v4");

    /**
     * Creates a listing for the caller-owned provider profile. The
     * {@code providerId} argument lives in the users.id space (V2
     * references users(id)) and is resolved through {@code findByUserId}
     * (A1) so the VERIFIED gate matches the profile owned by that user id.
     *
     * <p>{@code @Observed} is on BOTH proxy-visible entry forms (the CodeRabbit
     * #270 Major, adopted): this six-argument overload is the GraphQL/SPI
     * entry point and the seven-argument form is the REST entry point. The
     * official AOP proxying rule (Spring Framework Reference, Proxying
     * Mechanisms — "self invocation ... will bypass the advice") means the
     * delegation from this overload to the full form runs unproxied, so the
     * full form's annotation alone leaves the GraphQL {@code createService}
     * mutation unobserved (it was observed before the I6 split — one
     * six-argument method). Each external call now crosses the proxy exactly
     * once at ITS entry form: exactly one observation per create, no
     * double-counting — the same inventory name {@code catalog.create.listing}
     * on both forms (the pin counts names, not methods).
     */
    @Observed(name = "catalog.create.listing")
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListingView create(UUID providerId, String title, String description,
                                      String category, Long priceCents, String currency) {
        return create(providerId, title, description, category, priceCents, currency, null);
    }

    /**
     * I6: full creation form with the optional guest capacity — {@code null}
     * leaves capacity undeclared. The value is already gated at the API
     * surface ({@code @Positive} Bean Validation); the entity floor and the
     * V44 CHECK constraint back it.
     */
    @Observed(name = "catalog.create.listing")
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListingView create(UUID providerId, String title, String description,
                                      String category, Long priceCents, String currency,
                                      Integer maxGuests) {
        providerLookupPort.findByUserId(providerId)
                .filter(p -> "VERIFIED".equals(p.status()))
                .orElseThrow(() -> new BadRequestException("Provider is not verified"));
        ProviderListing listing = ProviderListing.create(providerId, title, description, category,
                priceCents, currency, maxGuests);
        ProviderListing saved = listingRepository.save(listing);
        eventPublisher.publishEvent(new ListingCreatedEvent(saved.getId()));
        eventPublisher.publishEvent(new CacheInvalidationRequested(CATALOG_CACHE_NAMES));
        return toProviderListingView(saved);
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing update(UUID id, String title, String description,
                                  String category, Long priceCents, Authentication authentication) {
        return update(id, title, description, category, priceCents, null, authentication);
    }

    /**
     * Updates the listing; blank currency keeps the stored ISO 4217 code
     * (omitting the field does not reset money semantics).
     */
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing update(UUID id, String title, String description,
                                  String category, Long priceCents, String currency,
                                  Authentication authentication) {
        return update(id, title, description, category, priceCents, currency, null, authentication);
    }

    /**
     * I6: full update form — capacity follows the currency contract
     * (omitted keeps the stored value; an explicit positive value
     * re-declares it).
     */
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing update(UUID id, String title, String description,
                                  String category, Long priceCents, String currency,
                                  Integer maxGuests, Authentication authentication) {
        ProviderListing listing = getById(id);
        verifyOwnership(listing, authentication);
        listing.update(title, description, category, priceCents, currency, maxGuests);
        eventPublisher.publishEvent(new CacheInvalidationRequested(CATALOG_CACHE_NAMES));
        return listing;
    }

    /**
     * L33: activation now resolves the publication window — an explicit
     * {@code expiresAt} wins; otherwise the configured policy (expiry-days
     * from now); with neither, a 409 ("no silently-immortal listing").
     */
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing activate(UUID id, Authentication authentication) {
        return activate(id, null, authentication);
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing activate(UUID id, java.time.Instant expiresAt,
                                    Authentication authentication) {
        ProviderListing listing = getById(id);
        verifyOwnership(listing, authentication);
        java.time.Instant now = clock.instant();
        java.time.Instant resolved = expiresAt != null
                ? expiresAt
                : policyExpiry(now);
        // CodeRabbit PR #299 round 1: a past or current expiry would make
        // the listing publicly ACTIVE until the next job tick — the
        // boundary is strictly future (the same rule renewal enforces).
        if (!resolved.isAfter(now)) {
            throw new com.marketplace.shared.api.BadRequestException(
                    "expiresAt must be strictly in the future");
        }
        listing.activate(resolved);
        // L35 (realestate systems plan §5 — saved searches and alerts): the
        // activation event, published inside the writer's transaction (the
        // BookingCreatedEvent/MediaUploadedEvent house pattern) so the Event
        // Publication Registry writes its entries atomically with the state
        // change. The publisher knows NOTHING about its consumers (the
        // search module's saved-search matcher today; the community layer's
        // L46 neighborhood bridge when its window opens — one publisher,
        // many consumers, the Modulith fan-out). Renewal (renew()) is a
        // different transition and deliberately does not fire this event.
        eventPublisher.publishEvent(new ListingActivatedEvent(id, listing.getProviderId()));
        eventPublisher.publishEvent(new CacheInvalidationRequested(CATALOG_CACHE_NAMES));
        return listing;
    }

    /**
     * L33 renewal: works only on the EXPIRED pause (a MANUAL pause answers
     * 409 — deliberate pauses stay until re-activated), bounded by the
     * cooldown (the anti-recycling floor: at most one renewal per window).
     * The new window runs from NOW by the policy.
     */
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing renew(UUID id, Authentication authentication) {
        ProviderListing listing = getById(id);
        verifyOwnership(listing, authentication);
        java.time.Instant now = clock.instant();
        if (listing.getRenewedAt() != null) {
            java.time.Instant cooldownEnd = listing.getRenewedAt()
                    .plus(java.time.Duration.ofDays(cooldownDays()));
            if (now.isBefore(cooldownEnd)) {
                throw new com.marketplace.shared.api.ConflictException(
                        "Listing " + id + " was renewed within the cooldown window ("
                                + cooldownDays() + " days)");
            }
        }
        listing.renew(now, policyExpiry(now));
        eventPublisher.publishEvent(new CacheInvalidationRequested(CATALOG_CACHE_NAMES));
        return listing;
    }

    /** The configured publication window, or the 409 policy gap. */
    private java.time.Instant policyExpiry(java.time.Instant now) {
        Integer days = catalogProperties.expiry().expiryDays();
        if (days == null) {
            throw new com.marketplace.shared.api.ConflictException(
                    "No expiry policy configured (marketplace.catalog.expiry.expiry-days)"
                            + " and no explicit expiry date provided");
        }
        return now.plus(java.time.Duration.ofDays(days));
    }

    private long cooldownDays() {
        Integer days = catalogProperties.expiry().renewalCooldownDays();
        return days != null && days > 0 ? days : 1;
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing pause(UUID id, Authentication authentication) {
        ProviderListing listing = getById(id);
        verifyOwnership(listing, authentication);
        listing.pause();
        eventPublisher.publishEvent(new CacheInvalidationRequested(CATALOG_CACHE_NAMES));
        return listing;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProviderListingSummary> findAllSummaries(Pageable pageable) {
        return listingRepository.findAll(pageable).map(this::toProviderListingSummary);
    }

    @Override
    @PreAuthorize("hasAnyRole('PROVIDER','ADMIN')")
    public ProviderListingSummary archiveListing(UUID id, Authentication authentication) {
        ProviderListing listing = getById(id);
        verifyOwnership(listing, authentication);
        listing.archive();
        eventPublisher.publishEvent(new CacheInvalidationRequested(CATALOG_CACHE_NAMES));
        return toProviderListingSummary(listing);
    }

    /**
     * Archives the caller’s own listing (ownership verified against the
     * users.id space per A1) and requests the catalog cache invalidation.
     */
    @PreAuthorize("hasAnyRole('PROVIDER','ADMIN')")
    public ProviderListing archive(UUID id, Authentication authentication) {
        ProviderListing listing = getById(id);
        verifyOwnership(listing, authentication);
        listing.archive();
        eventPublisher.publishEvent(new CacheInvalidationRequested(CATALOG_CACHE_NAMES));
        return listing;
    }

    /**
     * Verifies the listing belongs to the calling user (admins bypass):
     * the listing's provider id is a user id (A1 — V2 references
     * users(id)), so the owner check resolves the user-owned profile via
     * {@code findByUserId}.
     */
    private void verifyOwnership(ProviderListing listing, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (currentUserProvider.isAdmin(authentication)) return;
        // A1: listing.getProviderId() lives in the users.id space (V2 references
        // users(id)) — resolve through findByUserId, not findById (provider_profiles.id).
        providerLookupPort.findByUserId(listing.getProviderId())
                .filter(provider -> provider.userId() != null && provider.userId().equals(currentUserId))
                .orElseThrow(() -> new AccessDeniedException("You do not own this listing"));
    }

    /**
     * Batch-resolve provider names for an entire page, then map to ListingSummary.
     * Avoids N+1 queries by calling resolveNames once per page.
     */
    private Page<ListingSummary> toSummaryPage(Page<ProviderListing> page) {
        Set<UUID> providerIds = page.getContent().stream()
                .map(ProviderListing::getProviderId)
                .collect(Collectors.toSet());
        Map<UUID, String> providerNames = providerNameResolver.resolveNames(providerIds);
        return page.map(listing -> new ListingSummary(
                listing.getId(),
                listing.getTitle(),
                listing.getCategory(),
                BigDecimal.valueOf(listing.getPriceCents(), 2),
                listing.getCurrency(),
                providerNames.getOrDefault(listing.getProviderId(), "Unknown Provider")
        ));
    }

    private ProviderListingView toProviderListingView(ProviderListing listing) {
        return new ProviderListingView(
                listing.getId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getCategory(),
                listing.getPriceCents(),
                listing.getCurrency(),
                listing.getProviderId(),
                listing.getStatus().name(),
                listing.getMaxGuests(),
                listing.getCreatedAt(),
                listing.getUpdatedAt(),
                listing.getExpiresAt(),
                listing.getPausedReason()
        );
    }

    private ProviderListingSummary toProviderListingSummary(ProviderListing listing) {
        return new ProviderListingSummary(
                listing.getId(),
                listing.getTitle(),
                listing.getCategory(),
                BigDecimal.valueOf(listing.getPriceCents(), 2),
                listing.getProviderId(),
                listing.getStatus().name(),
                listing.getCreatedAt(),
                listing.getUpdatedAt()
        );
    }
}
