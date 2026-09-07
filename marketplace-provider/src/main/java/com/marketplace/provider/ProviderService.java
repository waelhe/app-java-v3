package com.marketplace.provider;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewStatsPort;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class ProviderService {

    private final ProviderRepository providerRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final ReviewStatsPort reviewStatsPort;

    private static final Set<String> PROVIDER_CACHE_NAMES = Set.of("providers");

    public ProviderService(ProviderRepository providerRepository,
                           CurrentUserProvider currentUserProvider,
                           ApplicationEventPublisher eventPublisher,
                           ReviewStatsPort reviewStatsPort) {
        this.providerRepository = providerRepository;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
        this.reviewStatsPort = reviewStatsPort;
    }

    @Observed(name = "provider.create")
    @PreAuthorize("hasRole('CONSUMER')")
    public ProviderProfile create(String displayName, String bio, UUID userId) {
        return providerRepository.save(ProviderProfile.create(displayName, bio, userId));
    }

    @Transactional(readOnly = true)
    @Cacheable("providers")
    public ProviderProfile getById(UUID id) {
        return providerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Provider not found: " + id));
    }

    @Observed(name = "provider.update")
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderProfile update(UUID id, String displayName, String bio, Authentication authentication) {
        ProviderProfile provider = getById(id);
        verifyOwnership(provider, authentication);
        provider.update(displayName, bio);
        eventPublisher.publishEvent(new CacheInvalidationRequested(PROVIDER_CACHE_NAMES, id));
        return provider;
    }

    @Observed(name = "provider.verify")
    @PreAuthorize("hasRole('ADMIN')")
    public ProviderProfile verify(UUID id) {
        ProviderProfile provider = getById(id);
        provider.verify();
        eventPublisher.publishEvent(new CacheInvalidationRequested(PROVIDER_CACHE_NAMES, id));
        return provider;
    }

    @Observed(name = "provider.suspend")
    @PreAuthorize("hasRole('ADMIN')")
    public ProviderProfile suspend(UUID id) {
        ProviderProfile provider = getById(id);
        provider.suspend();
        eventPublisher.publishEvent(new CacheInvalidationRequested(PROVIDER_CACHE_NAMES, id));
        return provider;
    }

    /**
     * L21 (roadmap §5): lands the event-driven rating average on the provider
     * profile. Called by {@code ProviderReviewStatsListener} from the async
     * AFTER_COMMIT dispatch of the review events — no {@code @PreAuthorize}
     * because there is no principal on that thread (same shape as
     * {@code PaymentsService#failIntent}, the webhook-driven write).
     *
     * <p><b>Concurrency (CI round-1 evidence):</b> two review events dispatched
     * close together run their listeners CONCURRENTLY on the same profile row —
     * the optimistic version rejects one and its aggregate is lost until the
     * event-publication resubmission retries it (minutes). The flow therefore
     * resolves the reviewed provider, takes a PESSIMISTIC_WRITE row lock, and
     * recomputes the aggregate INSIDE the locked transaction: the listeners
     * serialize, and the last one to apply always carries the freshest
     * {@code AVG} — no lost update, no stale regression. A missing profile
     * (deleted provider) is a silent skip, not an exception — an exception
     * would keep the publication incomplete and retry forever.
     */
    @Observed(name = "provider.rating.stats")
    public void refreshRatingAverage(UUID reviewId) {
        reviewStatsPort.findStatsByReviewId(reviewId).ifPresent(initial ->
                providerRepository.findByIdForUpdate(initial.providerId()).ifPresent(profile ->
                        reviewStatsPort.findStatsByProviderId(profile.getId()).ifPresent(fresh -> {
                            profile.applyRatingAverage(fresh.averageRating());
                            eventPublisher.publishEvent(
                                    new CacheInvalidationRequested(PROVIDER_CACHE_NAMES, profile.getId()));
                        })));
    }

    private void verifyOwnership(ProviderProfile provider, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!currentUserProvider.isAdmin(authentication)
                && (provider.getUserId() == null || !provider.getUserId().equals(currentUserId))) {
            throw new AccessDeniedException("You do not own this provider");
        }
    }
}
