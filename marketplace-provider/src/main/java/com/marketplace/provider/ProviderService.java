package com.marketplace.provider;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
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

/**
 * W5b (the journeys register's J29 row — the provider team delegation): the
 * ownership-gate family this service hosts now has TWO members — the
 * owner-only law ({@link #verifyOwnership}, unchanged: the verification
 * claim and every "me" surface) and the acting-provider law
 * ({@link #requireActingProvider}, the declared profile update): the owner
 * OR a live team seat with role MANAGER or EDITOR, resolved through
 * {@link ProviderTeamService#isActiveEditorOrAbove} — the delegation's
 * write floor. The inline owner branch runs FIRST so the owner's own
 * authority never depends on the delegation registry (and the team
 * service's answer is only consulted for a non-owner — one indexed read
 * per delegated write, never on the owner's happy path).
 */
@Service
@Transactional
public class ProviderService {

    private final ProviderRepository providerRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final ReviewStatsPort reviewStatsPort;
    private final ProviderTeamService teamService;

    private static final Set<String> PROVIDER_CACHE_NAMES = Set.of("providers");

    public ProviderService(ProviderRepository providerRepository,
                           CurrentUserProvider currentUserProvider,
                           ApplicationEventPublisher eventPublisher,
                           ReviewStatsPort reviewStatsPort,
                           ProviderTeamService teamService) {
        this.providerRepository = providerRepository;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
        this.reviewStatsPort = reviewStatsPort;
        this.teamService = teamService;
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
     *
     * <p><b>The one-live guard (V244's contract):</b> an account holds at
     * most ONE live provider profile. The pre-write check answers the
     * friendly 409 ({@code ConflictException}, the dictionary surfaces'
     * division of labor); the partial unique index
     * {@code uq_provider_profiles_one_live} answers the concurrent-race 409
     * (the L31/23505 law, the V233 {@code uq_provider_claims_one_live}
     * precedent verbatim). {@code findByUserId} reads only live rows
     * (Hibernate {@code @SoftDelete}), so a withdrawn profile frees the
     * account to onboard again — the "revocation frees the slot" semantics.
     */
    @Observed(name = "provider.create")
    @PreAuthorize("hasRole('CONSUMER')")
    public ProviderProfile create(String displayName, String bio, UUID userId,
                                  ProviderActorType actorType, String agencyName,
                                  String licenseNumber) {
        providerRepository.findByUserId(userId).ifPresent(existing -> {
            throw new ConflictException(
                    "Provider profile already exists for this account (id " + existing.getId() + ")");
        });
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
     *
     * <p><b>W5b (J29 — the delegation's write floor):</b> the gate on this
     * surface is the ACTING-provider law — the owner OR a live team seat
     * with role MANAGER/EDITOR — so a delegated employee keeps the
     * business's own profile current (the register's «تفويض فريق بصلاحيات
     * ونطاق»). A stranger keeps the exact pre-W5b semantics: the 404 for
     * an unknown provider, the 403 for a known one owned by someone else.
     */
    @Observed(name = "provider.update")
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderProfile update(UUID id, String displayName, String bio, ProviderActorType actorType,
                                  String agencyName, String licenseNumber, Authentication authentication) {
        ProviderProfile provider = getById(id);
        requireActingProvider(provider, authentication);
        provider.update(displayName, bio, actorType, agencyName, licenseNumber);
        eventPublisher.publishEvent(new CacheInvalidationRequested(PROVIDER_CACHE_NAMES, id));
        return provider;
    }

    @Observed(name = "provider.fulfillment-location.update")
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderProfile updateMyFulfillmentLocation(String address, double latitude, double longitude,
                                                        Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        ProviderProfile provider = providerRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Verified provider profile not found"));
        if (provider.getStatus() != ProviderStatus.VERIFIED) {
            throw new ConflictException("Only a verified provider can enable marketplace delivery");
        }
        provider.updateFulfillmentLocation(address, latitude, longitude);
        eventPublisher.publishEvent(new CacheInvalidationRequested(PROVIDER_CACHE_NAMES, provider.getId()));
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
     * W2 (yelp-level plan §5 — the business page): the owner submits the
     * ownership-verification claim — {@code UNVERIFIED/REJECTED/VERIFIED
     * → PENDING} (the entity's transition law). The claim is queued for
     * the administrative resolution below, never believed on arrival.
     */
    @Observed(name = "provider.verification.submit")
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderProfile submitVerification(UUID id, Authentication authentication) {
        ProviderProfile provider = getById(id);
        verifyOwnership(provider, authentication);
        provider.submitForVerification();
        eventPublisher.publishEvent(new CacheInvalidationRequested(PROVIDER_CACHE_NAMES, id));
        return provider;
    }

    /**
     * W2: an administrator confirms ownership — {@code PENDING → VERIFIED}
     * (the «مالك موثّق» badge lights; display-only — no privilege attaches,
     * the profile status lifecycle above stays the platform gate).
     */
    @Observed(name = "provider.verification.confirm")
    @PreAuthorize("hasRole('ADMIN')")
    public ProviderProfile confirmVerification(UUID id) {
        ProviderProfile provider = getById(id);
        provider.confirmVerification();
        eventPublisher.publishEvent(new CacheInvalidationRequested(PROVIDER_CACHE_NAMES, id));
        return provider;
    }

    /**
     * W2: an administrator declines the claim — {@code PENDING → REJECTED}
     * (the owner may submit again; the Envers trail is the record).
     */
    @Observed(name = "provider.verification.reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ProviderProfile rejectVerification(UUID id) {
        ProviderProfile provider = getById(id);
        provider.rejectVerification();
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

    /**
     * W5b (J29): the ACTING-provider gate — the delegated surfaces' law.
     * The admin bypass and the owner branch are {@link #verifyOwnership}'s
     * exact rule (unchanged); the THIRD branch is the delegation: a live
     * team seat with role MANAGER or EDITOR answers for the business
     * ({@code ProviderTeamService.isActiveEditorOrAbove} — the write
     * floor). Everything else keeps the house 403 and its exact message,
     * so a stranger's request is indistinguishable from the pre-W5b
     * behavior on every surface this gate serves.
     */
    private void requireActingProvider(ProviderProfile provider, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (currentUserProvider.isAdmin(authentication)
                || (provider.getUserId() != null && provider.getUserId().equals(currentUserId))) {
            return;
        }
        if (!teamService.isActiveEditorOrAbove(provider.getId(), currentUserId)) {
            throw new AccessDeniedException("You do not own this provider");
        }
    }
}
