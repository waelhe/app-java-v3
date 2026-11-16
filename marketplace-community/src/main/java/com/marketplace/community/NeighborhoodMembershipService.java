package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.MembershipVerificationGrantedEvent;
import com.marketplace.shared.api.NewListingInNeighborhoodEvent;
import com.marketplace.shared.api.PropertyDetailsPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ConflictException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * The neighborhood membership surface (neighborhood community plan §5-L41
 * — the anchor). Three commands, one gate order, all measured from the
 * house precedents:
 *
 * <p><b>The write gate order (realestate L31's own discipline):</b> the
 * location is resolved through {@link GeoLookupPort} FIRST — an unknown
 * node is the port's own 404, never a silently-accepted value — then the
 * level-3 requirement answers 400 <em>before any write</em> (the
 * type-gate philosophy; D-R5's own shape). Only then does the
 * transaction touch a row.
 *
 * <p><b>The switch's flush ordering (a measured Hibernate fact):</b>
 * Hibernate's ActionQueue flushes INSERTs before UPDATEs. A membership
 * switch is a soft-delete UPDATE of the old row plus an INSERT of the
 * new one — if both queue up together, the INSERT reaches V60's partial
 * unique index {@code ON (user_id) WHERE is_deleted = FALSE} while the
 * old row is still active and the database answers 23505. The explicit
 * {@code delete + flush} between the two writes makes the soft-delete
 * land first; the whole pair stays inside the ONE service transaction,
 * so a failure at the insert rolls the soft-delete back with it (the
 * plan's criterion 5 — the switch is atomic by transaction, not by
 * luck).
 *
 * <p><b>The leave contract (G-N1):</b> the soft delete releases the
 * partial index's slot — a later rejoin inserts a fresh row whose
 * {@code member_since} honestly restarts the membership clock.
 *
 * <p><b>The L46 realestate bridge (neighborhood community plan §5-L46):</b>
 * the membership aggregate's own query powers the community side of the
 * catalog activation fan-out — {@link #onListingActivated(UUID, UUID)}
 * resolves the listing's neighborhood through {@link PropertyDetailsPort}
 * and publishes one {@link NewListingInNeighborhoodEvent} per ACTIVE
 * member (the publisher's own membership excepted). The bridge lives on
 * THIS service because the member resolution is the membership domain's
 * own read — the same ownership {@code SavedSearchService.
 * processListingActivated} proved for the search side of the same event
 * (realestate plan §5-L35, one publisher, many consumers).
 */
@Service
@Transactional
public class NeighborhoodMembershipService {

    /**
     * The geo port's own level contract (GeoNode's javadoc): 3 =
     * neighborhood. An int constant, not the geo module's enum — the
     * cross-module vocabulary IS the port's int (the enum stays inside
     * the geo module's boundary).
     */
    static final int NEIGHBORHOOD_LEVEL = 3;

    private final NeighborhoodMembershipRepository repository;
    private final GeoLookupPort geoLookupPort;
    private final PropertyDetailsPort propertyDetailsPort;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public NeighborhoodMembershipService(NeighborhoodMembershipRepository repository,
                                         GeoLookupPort geoLookupPort,
                                         PropertyDetailsPort propertyDetailsPort,
                                         ApplicationEventPublisher eventPublisher,
                                         Clock clock) {
        this.repository = repository;
        this.geoLookupPort = geoLookupPort;
        this.propertyDetailsPort = propertyDetailsPort;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Join — or switch. PUT semantics: joining the neighborhood the
     * caller is already in is an idempotent no-op returning the stored
     * row (the caller learns nothing changed from the response's own
     * timestamps); joining a different neighborhood is the atomic switch
     * (soft-delete the old, insert the new, one transaction).
     *
     * @return the ACTIVE membership after the command — {@code true} when
     *         a new row was created (join or switch), {@code false} when
     *         the stored row already answered (idempotent re-join)
     */
    @Observed(name = "community.membership.join")
    public MembershipCommandResult join(UUID userId, UUID locationId) {
        GeoLookupPort.GeoNode node = geoLookupPort.getLocation(locationId);
        if (node.level() != NEIGHBORHOOD_LEVEL) {
            throw new BadRequestException(
                    "locationId must reference a level-3 neighborhood node, got level "
                            + node.level() + " (" + node.slug() + ")");
        }
        Optional<NeighborhoodMembership> existing = repository.findByUserId(userId);
        if (existing.isPresent()) {
            if (existing.get().getLocationId().equals(locationId)) {
                return new MembershipCommandResult(
                        NeighborhoodMembershipView.of(existing.get()), false);
            }
            repository.delete(existing.get());
            // The soft-delete UPDATE must reach the database BEFORE the
            // new INSERT queues its unique-index check (Hibernate flushes
            // inserts before updates — see the class javadoc). One
            // transaction: a later failure rolls this back with the insert.
            repository.flush();
        }
        // The #484 review round's verdict carry: the REJECTED verdict is
        // the one admin decision that follows the USER — read the latest
        // row INCLUDING soft-deleted ones BEFORE creating the fresh claim,
        // so a leave/rejoin or a neighborhood switch cannot mint an
        // UNVERIFIED row that resurrects the write gate the administrator
        // closed. Every other state births UNVERIFIED (the status quo).
        NeighborhoodMembership fresh = NeighborhoodMembership.join(userId, locationId, clock);
        if (MembershipVerificationState.REJECTED.name()
                .equals(repository.findLatestVerificationStateIncludingDeleted(userId))) {
            fresh.inheritRejectedVerdict();
        }
        NeighborhoodMembership saved = repository.save(fresh);
        return new MembershipCommandResult(NeighborhoodMembershipView.of(saved), true);
    }

    /**
     * The caller's ACTIVE membership — 404 when they never joined (the
     * /me convention: an absent resource is an honest 404, never an
     * empty 200 that pretends the concept exists).
     */
    @Transactional(readOnly = true)
    public NeighborhoodMembershipView getMine(UUID userId) {
        return repository.findByUserId(userId)
                .map(NeighborhoodMembershipView::of)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No neighborhood membership — join one first (PUT /api/v1/me/neighborhood)"));
    }

    /**
     * Leave: the soft delete frees the G-N1 slot (the partial index).
     * Leaving without a membership is an honest 404 — the same /me
     * convention as the read.
     */
    @Observed(name = "community.membership.leave")
    public void leave(UUID userId) {
        NeighborhoodMembership membership = repository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No neighborhood membership to leave"));
        repository.delete(membership);
    }

    /**
     * Manual, provider-free verification request. An administrator is the
     * first runnable verifier — and the only one who can UNDO a verdict:
     * the #484 review round closed the measured self-reversal hole (a
     * rejected member re-requesting moved REJECTED → PENDING and regained
     * the write gate without any review), so a REJECTED claim now answers
     * its own explicit 409 — the member's ONE honest recovery path is an
     * administrator's APPROVE (the re-admission lever); leave-and-rejoin
     * carries the verdict forward, so the fresh row is born REJECTED.
     */
    @Observed(name = "community.membership.verification.request")
    public NeighborhoodMembershipView requestVerification(UUID userId) {
        NeighborhoodMembership membership = repository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No neighborhood membership to verify"));
        MembershipVerificationState state = membership.getVerificationState();
        if (state == MembershipVerificationState.REJECTED) {
            throw new ConflictException(
                    "Membership verification was rejected — only an administrator review can change that verdict");
        }
        if (state == MembershipVerificationState.PENDING
                || state == MembershipVerificationState.VERIFIED) {
            throw new ConflictException("Membership verification is already " + state);
        }
        membership.requestVerification();
        return NeighborhoodMembershipView.of(repository.save(membership));
    }

    /**
     * The administrative review — the verdict's ONLY mover, in BOTH
     * directions (the #484 review round's completion): APPROVE admits a
     * PENDING claim and RE-ADMITS a REJECTED one (the recovery lever
     * that makes the carried verdict honest — without it, a rejection
     * inherited across a rejoin would be a permanent user-level ban with
     * no recourse); REJECT refuses a PENDING claim. Nothing else in the
     * system moves these states: the member-controlled request only
     * UNVERIFIED → PENDING.
     */
    @Observed(name = "community.membership.verification.review")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public NeighborhoodMembershipView reviewVerification(UUID membershipId, boolean approve) {
        NeighborhoodMembership membership = repository.findById(membershipId)
                .orElseThrow(() -> new ResourceNotFoundException("NeighborhoodMembership", membershipId));
        try {
            if (approve) {
                membership.approveVerification();
            } else {
                membership.rejectVerification();
            }
        } catch (IllegalStateException invalidTransition) {
            throw new ConflictException(invalidTransition.getMessage());
        }
        NeighborhoodMembership saved = repository.save(membership);
        if (approve) {
            // B-17 (C.9 — the CodeRabbit round-1 adoption closing the wiring
            // gap the review measured: the grant verdict is a PUBLISHED fact,
            // never a method call the consumer must know about. Both grant
            // paths (PENDING -> VERIFIED and REJECTED -> VERIFIED) fire it —
            // approveVerification() guards the transition itself. Published
            // inside the reviewer's own transaction (Modulith
            // reference/events.html): the registry entry commits atomically
            // with the verdict, and the notifications listener delivers
            // AFTER_COMMIT in its own unit.
            eventPublisher.publishEvent(new MembershipVerificationGrantedEvent(
                    saved.getId(), saved.getUserId(), saved.getLocationId()));
        }
        return NeighborhoodMembershipView.of(saved);
    }

    /**
     * The verification queue's complete drain order (D-N5's determinism
     * rule): the state's own clock — {@code updatedAt} ASC (the request's
     * UNVERIFIED→PENDING transition is the row's last write, so the oldest
     * pending claim drains first), {@code id} breaking ties. The admin
     * read rides the moderation queue's own shape: the optional state axis
     * filters one state, absent = the whole membership ledger, read-only,
     * soft-deleted rows hidden by the derived query's own filter.
     */
    private static final Sort VERIFICATION_QUEUE_SORT =
            Sort.by(Sort.Direction.ASC, "updatedAt", "id");

    /**
     * The administrative verification queue (the lifecycle's review
     * surface): {@code GET /api/v1/admin/neighborhood-memberships}. A null
     * state reads every ACTIVE membership (the ledger); a state filters to
     * its own page — PENDING is the reviewable queue, REJECTED/VERIFIED the
     * decision trails, UNVERIFIED the untouched floor.
     */
    @Observed(name = "community.membership.verification.queue")
    @Transactional(readOnly = true)
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public Page<NeighborhoodMembershipView> getVerificationQueue(
            MembershipVerificationState state, Pageable pageable) {
        Pageable queuePageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), VERIFICATION_QUEUE_SORT);
        Page<NeighborhoodMembership> page = state == null
                ? repository.findAll(queuePageable)
                : repository.findByVerificationState(state, queuePageable);
        return page.map(NeighborhoodMembershipView::of);
    }

    /**
     * The join command's outcome: the resulting view plus whether a row
     * was created (join/switch ⇒ 201 Created) or already answered
     * (idempotent re-join ⇒ 200 OK) — the controller's status decision
     * is the command's own fact, not a guess.
     */
    record MembershipCommandResult(NeighborhoodMembershipView view, boolean created) {
    }

    /**
     * L46 (neighborhood community plan §5 — the community realestate
     * bridge): one catalog activation reaching the members of the
     * listing's neighborhood. The catalog module stays unaware of every
     * consumer ("صفر معرفة بالبحوث" — the L35 discipline verbatim): it
     * published {@code ListingActivatedEvent} inside its activation
     * transaction, and THIS unit — running AFTER_COMMIT in its own
     * transaction via the thin {@link NeighborhoodListingEventListener}
     * — resolves the property's {@code locationId} through
     * {@link PropertyDetailsPort} and publishes one
     * {@link NewListingInNeighborhoodEvent} per ACTIVE member of that
     * node.
     *
     * <p><b>The documented skip (criterion 2):</b> a listing with no
     * {@code property_details} row (not real-estate) has no neighborhood
     * to bridge — the method returns 0 notifications. A property in a
     * node nobody joined behaves identically through the query itself
     * (criterion 5: zero notifications, zero errors — no level gate, no
     * exception; the ACTIVE-membership query IS the scope).
     *
     * <p><b>The conflict-of-interest exclusion (criterion 4):</b> the
     * listing's own publisher, when they are ALSO a member of that
     * neighborhood, is the one member this bridge deliberately does not
     * alert — nobody gets a "new listing" notification for their own
     * activation.
     *
     * <p><b>The retry contract (criterion 3):</b> the per-member event
     * publications commit atomically with this unit — a failure anywhere
     * (the port lookup, the member query, a publication) rolls back
     * every publication of this run and the framework's registry keeps
     * the {@code ListingActivatedEvent} entry incomplete for retry, so
     * a failed bridge run never silently drops the match. Fan-out is
     * linear on member count by design (the plan's declared D-C1 debt,
     * closure at the measured threshold).
     *
     * <p><b>No {@code @Observed} of its own:</b> async listener-side
     * runs stay outside the observation inventory by the pinned policy
     * (business commands observed, listener runs not — the
     * {@code SavedSearchService.processListingActivated} precedent for
     * this very event); the bridge's health is observable through the
     * publication registry and the listener's own log line.
     *
     * @param listingId  the activated catalog listing
     * @param providerId the listing's publisher (the users.id-space fact
     *                   {@code ListingActivatedEvent} carries) — used
     *                   only for the self-notification exclusion
     * @return how many members were alerted (0 for the documented skips)
     */
    public int onListingActivated(UUID listingId, UUID providerId) {
        Optional<PropertyDetailsPort.PropertyView> property =
                propertyDetailsPort.findByListingId(listingId);
        if (property.isEmpty()) {
            // Not a real-estate listing — no neighborhood to bridge.
            return 0;
        }
        UUID locationId = property.get().locationId();
        int alerted = 0;
        for (NeighborhoodMembership member : repository.findByLocationId(locationId)) {
            if (member.getUserId().equals(providerId)) {
                continue;
            }
            eventPublisher.publishEvent(
                    new NewListingInNeighborhoodEvent(member.getUserId(), listingId));
            alerted++;
        }
        return alerted;
    }
}
