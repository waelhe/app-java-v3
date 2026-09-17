package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
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
    private final Clock clock;

    public NeighborhoodMembershipService(NeighborhoodMembershipRepository repository,
                                         GeoLookupPort geoLookupPort,
                                         Clock clock) {
        this.repository = repository;
        this.geoLookupPort = geoLookupPort;
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
        NeighborhoodMembership saved =
                repository.save(NeighborhoodMembership.join(userId, locationId, clock));
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
     * The join command's outcome: the resulting view plus whether a row
     * was created (join/switch ⇒ 201 Created) or already answered
     * (idempotent re-join ⇒ 200 OK) — the controller's status decision
     * is the command's own fact, not a guess.
     */
    record MembershipCommandResult(NeighborhoodMembershipView view, boolean created) {
    }
}
