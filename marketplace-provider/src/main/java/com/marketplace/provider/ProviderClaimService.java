package com.marketplace.provider;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * J14 (the journeys register — «مطالبة صاحب العمل بالملف»): the business
 * claim cycle's engine, on the {@link ProviderService}/{@code UrgentAlertService}
 * house shapes — constructor injection, {@code @Transactional} +
 * {@code @Observed} on every command, the gate order BEFORE any write
 * (the {@code InstitutionService#register} discipline), the state
 * machine's {@link IllegalStateException} translated to the house 409 at
 * this seam, and the cache invalidation event on every profile write
 * (the {@code ProviderService} rule: {@code getById} is the cached read).
 *
 * <p><b>The cycle (the register's gap, closed):</b> the platform pre-seeds
 * the claimable page ({@link #createUnclaimedProfile} — ADMIN, user_id
 * NULL); a member claims it ({@link #submitClaim} — one live PENDING
 * claim per page, the friendly 409 first and the V233 unique index as
 * the race backstop); an administrator reviews ({@link #review} — APPROVE
 * hands the ownership over: provider_profiles.user_id = claimant AND
 * claimed_at set, in the SAME transaction as the claim's state flip; REJECT
 * leaves the page unowned and claimable again — the claimant's recovery
 * is a FRESH claim); the claimant may withdraw their own PENDING claim
 * ({@link #withdraw} — the other 403/409 gates live here, the ownership
 * one first). {@link #claims} is the administrative queue.
 *
 * <p><b>Actor attribution (the identity SPI convention):</b> the actor
 * strings ride the decision columns ({@code decided_by} — the admin's
 * verdict on review, the claimant's own user id on withdrawal) and the
 * BaseEntity audit columns land through the auditing listener from the
 * same authentication the {@code @PreAuthorize} gates read.
 */
@Service
@Transactional
public class ProviderClaimService {

    private static final Set<String> PROVIDER_CACHE_NAMES = Set.of("providers");

    /**
     * The review queue's drain order (the {@code UrgentAlertService}
     * VERIFICATION_QUEUE discipline): oldest claim first on the complete
     * {@code (updatedAt ASC, id ASC)} key — the board reads a stable,
     * fair order.
     */
    private static final Sort CLAIM_QUEUE_SORT =
            Sort.by(Sort.Direction.ASC, "updatedAt").and(Sort.by(Sort.Direction.ASC, "id"));

    private final ProviderClaimRepository claimRepository;
    private final ProviderRepository providerRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ProviderClaimService(ProviderClaimRepository claimRepository,
                                ProviderRepository providerRepository,
                                ApplicationEventPublisher eventPublisher,
                                Clock clock) {
        this.claimRepository = claimRepository;
        this.providerRepository = providerRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * The platform's own pre-seed (ADMIN): the CLAIMABLE business page —
     * {@code user_id} NULL by construction, status PENDING (the platform's
     * verification gate, like every profile's day one), ownership badge
     * UNVERIFIED, {@code claimed_at} NULL. No claim exists yet; the page
     * waits for its owner to arrive through {@link #submitClaim}.
     */
    @Observed(name = "provider.claim.profile.seed")
    @PreAuthorize("hasRole('ADMIN')")
    public ProviderProfile createUnclaimedProfile(ProviderRequest request, String actor) {
        ProviderProfile seeded = providerRepository.save(ProviderProfile.createUnclaimed(
                request.displayName(), request.bio(), request.actorType(),
                request.agencyName(), request.licenseNumber()));
        eventPublisher.publishEvent(new CacheInvalidationRequested(PROVIDER_CACHE_NAMES, seeded.getId()));
        return seeded;
    }

    /**
     * The claim submission. The gate order runs BEFORE any write (the
     * house rule): the page exists (404), the page is NOT already owned
     * ({@code user_id} NULL — else 409, the self-onboarded pages are not
     * claimable), and no live PENDING claim exists on it (409 — «بناء
     * الدورة بمالك واحد», one claim in flight). The V233 partial unique
     * index {@code uq_provider_claims_one_live} is the race backstop
     * under the guard: two concurrent submits on the same page — exactly
     * one INSERT survives, the loser's
     * {@link DataIntegrityViolationException} is translated to the same
     * friendly 409 (the L31/23505 law, rendered with the domain's own
     * words instead of the generic handler body).
     */
    @Observed(name = "provider.claim.submit")
    public ProviderClaim submitClaim(UUID providerId, UUID claimantUserId,
                                     String contactChannel, String note) {
        ProviderProfile profile = providerRepository.findById(providerId)
                .orElseThrow(() -> new ResourceNotFoundException("Provider not found: " + providerId));
        if (profile.getUserId() != null) {
            throw new ConflictException("Provider profile " + providerId
                    + " is already owned — only the platform's unclaimed pages can be claimed");
        }
        if (claimRepository.existsByProviderIdAndState(providerId, ProviderClaimState.PENDING)) {
            throw new ConflictException("Provider profile " + providerId
                    + " already has a live claim under review — one claim at a time");
        }
        ProviderClaim claim = ProviderClaim.submit(providerId, claimantUserId, contactChannel, note);
        try {
            return claimRepository.save(claim);
        } catch (DataIntegrityViolationException e) {
            // The concurrent-race loss (the unique index fired between the
            // guard and the insert) — the SAME 409 the guard answers, with
            // the domain's own words (the GlobalExceptionHandler's generic
            // 23505 body stays the last resort for every other integrity
            // failure).
            throw new ConflictException("Provider profile " + providerId
                    + " already has a live claim under review — one claim at a time");
        }
    }

    /**
     * The verdict's ONLY mover (ADMIN — the {@code UrgentAlertService}
     * review discipline verbatim): the claim exists (404) and is PENDING
     * (409 otherwise — the decided states are terminal). APPROVE links
     * the ownership — {@code provider_profiles.user_id = claimant} AND
     * {@code claimed_at} set, in the SAME transaction as the claim's own
     * APPROVED flip (one decision, one write pair, one Envers revision
     * each — a crash hands over nothing). REJECT leaves the page unowned
     * and claimable again: the claimant's recovery path is a FRESH claim
     * (the live-claim index no longer blocks once the state moves).
     */
    @Observed(name = "provider.claim.review")
    @PreAuthorize("hasRole('ADMIN')")
    public ProviderClaim review(UUID claimId, boolean approve, String adminActor, String note) {
        ProviderClaim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Provider claim not found: " + claimId));
        if (claim.getState() != ProviderClaimState.PENDING) {
            throw new ConflictException("Only PENDING claims can be reviewed — claim "
                    + claimId + " is " + claim.getState());
        }
        ProviderProfile profile = providerRepository.findById(claim.getProviderId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Provider not found: " + claim.getProviderId()));
        Instant now = clock.instant();
        try {
            if (approve) {
                // The profile-side handover FIRST — the riskier write: if
                // the page gained an owner between submission and verdict,
                // the refusal lands BEFORE the claim's own state flip
                // (the transaction rolls both back in production; the
                // order keeps the in-memory claim untouched on refusal
                // too — a crash or a refusal hands over nothing).
                profile.claimOwnership(claim.getClaimantUserId(), now);
                claim.approve(adminActor, note, now);
            } else {
                claim.reject(adminActor, note, now);
            }
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
        eventPublisher.publishEvent(new CacheInvalidationRequested(PROVIDER_CACHE_NAMES, profile.getId()));
        return claim;
    }

    /**
     * The claimant's own recovery lever: the claim exists (404), is
     * PENDING (409 otherwise), and is the CLAIMANT's own (403 otherwise —
     * a claim is withdrawn by the hand that submitted it or by nobody).
     * WITHDRAWN frees the page for a fresh claim from anyone.
     */
    @Observed(name = "provider.claim.withdraw")
    public ProviderClaim withdraw(UUID claimId, UUID claimantUserId) {
        ProviderClaim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Provider claim not found: " + claimId));
        if (claim.getState() != ProviderClaimState.PENDING) {
            throw new ConflictException("Only PENDING claims can be withdrawn — claim "
                    + claimId + " is " + claim.getState());
        }
        if (!claim.getClaimantUserId().equals(claimantUserId)) {
            throw new AccessDeniedException("You do not own this provider claim");
        }
        try {
            claim.withdraw(claimantUserId.toString(), clock.instant());
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
        return claim;
    }

    /**
     * The administrative queue (the {@code UrgentAlertService#reviewQueue}
     * shape): the optional provider and state axes over the live rows,
     * drained oldest-first on the complete {@code (updatedAt, id)} key —
     * the review board reads a stable, fair order.
     */
    @Transactional(readOnly = true)
    public Page<ProviderClaim> claims(UUID providerId, ProviderClaimState state, Pageable pageable) {
        Pageable queuePageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), CLAIM_QUEUE_SORT);
        if (providerId != null && state != null) {
            return claimRepository.findByProviderIdAndState(providerId, state, queuePageable);
        }
        if (providerId != null) {
            return claimRepository.findByProviderId(providerId, queuePageable);
        }
        if (state != null) {
            return claimRepository.findByState(state, queuePageable);
        }
        return claimRepository.findAll(queuePageable);
    }
}
