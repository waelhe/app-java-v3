package com.marketplace.catalog;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.CatalogSearchPort;
import org.springframework.modulith.NamedInterface;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ListingCreatedEvent;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderListingSummary;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.api.SearchCriteria;
import com.marketplace.shared.api.BadRequestException;
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
import java.util.Map;
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

    public CatalogService(ProviderListingRepository listingRepository,
                          CurrentUserProvider currentUserProvider,
                          ProviderNameResolver providerNameResolver,
                          ApplicationEventPublisher eventPublisher,
                          ProviderLookupPort providerLookupPort) {
        this.listingRepository = listingRepository;
        this.currentUserProvider = currentUserProvider;
        this.providerNameResolver = providerNameResolver;
        this.eventPublisher = eventPublisher;
        this.providerLookupPort = providerLookupPort;
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
        return listingRepository.findByProviderId(providerId, pageable);
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
        Long minPrice = criteria.minPrice() != null ? criteria.minPrice().movePointRight(2).longValue() : null;
        Long maxPrice = criteria.maxPrice() != null ? criteria.maxPrice().movePointRight(2).longValue() : null;
        Page<ProviderListing> page = listingRepository.searchByCriteria(criteria.category(), minPrice, maxPrice, pageable);
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
     * {@code search-results-v2} cache (criteria-keyed, window included) is
     * the caching surface for window searches.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ListingSummary> searchByCriteriaRestricted(SearchCriteria criteria, Set<UUID> providerIds, Pageable pageable) {
        Long minPrice = criteria.minPrice() != null ? criteria.minPrice().movePointRight(2).longValue() : null;
        Long maxPrice = criteria.maxPrice() != null ? criteria.maxPrice().movePointRight(2).longValue() : null;
        Page<ProviderListing> page = listingRepository.searchByCriteriaRestricted(
                criteria.category(), minPrice, maxPrice, providerIds, pageable);
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

    // Cache names are NAMESPACED BY SCHEMA VERSION (CodeRabbit #241): the four
    // ListingSummary caches hold JDK-serialized records — a record-component
    // change (currency was added by the B4 layer) lets a stale pre-change entry
    // deserialize with null components and serve it as a cache hit, bypassing
    // the mapping that populates the new component. Bumping the name with every
    // ListingSummary component change evicts at deploy time through the deploy
    // itself (old entries become unreachable and expire via the 1h TTL). Any
    // future change to ListingSummary MUST bump this suffix — pinned by
    // ListingSummaryCacheContractFilesTest.
    private static final Set<String> CATALOG_CACHE_NAMES =
            Set.of("catalog-active-v2", "catalog-by-category-v2", "catalog-search-v2", "search-results-v2");

    /**
     * Creates a listing for the caller-owned provider profile. The
     * {@code providerId} argument lives in the users.id space (V2
     * references users(id)) and is resolved through {@code findByUserId}
     * (A1) so the VERIFIED gate matches the profile owned by that user id.
     */
    @Observed(name = "catalog.create.listing")
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListingView create(UUID providerId, String title, String description,
                                      String category, Long priceCents, String currency) {
        providerLookupPort.findByUserId(providerId)
                .filter(p -> "VERIFIED".equals(p.status()))
                .orElseThrow(() -> new BadRequestException("Provider is not verified"));
        ProviderListing listing = ProviderListing.create(providerId, title, description, category,
                priceCents, currency);
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
        ProviderListing listing = getById(id);
        verifyOwnership(listing, authentication);
        listing.update(title, description, category, priceCents, currency);
        eventPublisher.publishEvent(new CacheInvalidationRequested(CATALOG_CACHE_NAMES));
        return listing;
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderListing activate(UUID id, Authentication authentication) {
        ProviderListing listing = getById(id);
        verifyOwnership(listing, authentication);
        listing.activate();
        eventPublisher.publishEvent(new CacheInvalidationRequested(CATALOG_CACHE_NAMES));
        return listing;
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
                listing.getCreatedAt(),
                listing.getUpdatedAt()
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
