package com.marketplace.provider;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewStats;
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

import java.util.Optional;
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
        return create(displayName, bio, userId, null, null, null);
    }

    /**
     * L36 full form: the persona fields ride the creation — a null actor
     * type is the individual default (the entity factory's own gate). Both
     * forms cross the proxy exactly once at their entry (the #270 rule: the
     * delegating overload is unproxied self-invocation, so BOTH forms
     * carry {@code @Observed}).
     */
    @Observed(name = "provider.create")
    @PreAuthorize("hasRole('CONSUMER')")
    public ProviderProfile create(String displayName, String bio, UUID userId,
                                  ProviderActorType actorType, String agencyName,
                                  String licenseNumber) {
        return providerRepository.save(
                ProviderProfile.create(displayName, bio, userId, actorType, agencyName, licenseNumber));
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
        return update(id, displayName, bio, null, null, null, authentication);
    }

    /**
     * L36 full form — the persona fields follow the entity's documented PUT
     * semantics (actorType null = keep; agencyName/licenseNumber null =
     * clear, the bio contract). Same entry-form rule as create: both forms
     * carry the observation.
     */
    @Observed(name = "provider.update")
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderProfile update(UUID id, String displayName, String bio, ProviderActorType actorType,
                                  String agencyName, String licenseNumber, Authentication authentication) {
        ProviderProfile provider = getById(id);
        verifyOwnership(provider, authentication);
        provider.update(displayName, bio, actorType, agencyName, licenseNumber);
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
     * L21 + W1 (§4.4): lands the event-driven rating PAIR on the provider
     * profile. Called by {@code ProviderReviewStatsListener} from the async
     * AFTER_COMMIT dispatch of the review events — no {@code @PreAuthorize}
     * because there is no principal on that thread (same shape as
     * {@code PaymentsService#failIntent}, the webhook-driven write).
     *
     * <p><b>The id-space correction (the plan's named measured defect):</b>
     * {@code reviews.provider_id} physically carries a {@code users.id}
     * (V6's FK), so the flow resolves the profile BY USER ID
     * ({@code findByUserIdForUpdate}) and recomputes the aggregates BY THE
     * SAME users.id ({@code profile.getUserId()} — the
     * {@code ProviderPublicPageService} pattern). The old flow looked the
     * users.id up in the {@code provider_profiles.id} space and then
     * queried the stats by {@code profile.getId()} AGAIN — a double
     * mismatch that made the path silently skip on every
     * production-shaped pair of id spaces.
     *
     * <p><b>Concurrency (CI round-1 evidence):</b> two review events
     * dispatched close together run their listeners CONCURRENTLY on the
     * same profile row — the optimistic version rejects one and its
     * aggregate is lost until the event-publication resubmission retries
     * it (minutes). The flow therefore takes a PESSIMISTIC_WRITE row lock
     * and recomputes both aggregates INSIDE the locked transaction: the
     * listeners serialize, and the last one to apply always carries the
     * freshest {@code AVG} — no lost update, no stale regression. A
     * missing profile (deleted provider) is a silent skip, not an
     * exception — an exception would keep the publication incomplete and
     * retry forever. An EMPTY aggregate (the last published review left
     * the surface — a moderation hide, for one) CLEARS the stored value:
     * the recompute is truth, and a stale number must not survive it.
     */
    @Observed(name = "provider.rating.stats")
    public void refreshRatingAverage(UUID reviewId) {
        reviewStatsPort.findProviderUserIdByReviewId(reviewId).ifPresent(providerUserId ->
                providerRepository.findByUserIdForUpdate(providerUserId).ifPresent(profile -> {
                    Optional<ReviewStats> verified =
                            reviewStatsPort.findStatsByProviderId(profile.getUserId());
                    Optional<ReviewStats> general =
                            reviewStatsPort.findGeneralStatsByProviderId(profile.getUserId());
                    profile.applyRatingAverage(verified.map(ReviewStats::averageRating).orElse(null));
                    profile.applyGeneralRating(general.map(ReviewStats::averageRating).orElse(null),
                            general.map(ReviewStats::reviewCount).orElse(0L));
                    eventPublisher.publishEvent(
                            new CacheInvalidationRequested(PROVIDER_CACHE_NAMES, profile.getId()));
                }));
    }

    private void verifyOwnership(ProviderProfile provider, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!currentUserProvider.isAdmin(authentication)
                && (provider.getUserId() == null || !provider.getUserId().equals(currentUserId))) {
            throw new AccessDeniedException("You do not own this provider");
        }
    }
}
