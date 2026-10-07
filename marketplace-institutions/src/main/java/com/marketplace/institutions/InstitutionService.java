package com.marketplace.institutions;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * B-13 (compliance plan C.3 — the owner's ruling «المؤسسات فيها جزء من
 * المجتمع»): the registry's engine — the register → review → publish
 * journey, on the {@code NeighborhoodMembershipService} house shapes:
 * the geo gate FIRST (the port's own 404 for an unknown node, the
 * level-3 requirement answering 400 BEFORE any write — realestate
 * L31's own discipline), the {@code CurrentUserProvider} /me seam, the
 * representative ownership, and the administrative review as the
 * verdict's ONLY mover.
 *
 * <p><b>The division the ruling itself draws:</b> this module owns the
 * institution EDGES (the registry, the JSON-LD, the institutional
 * verification). The MEMBERSHIP machinery stays in community — an
 * institution's neighborhood-membership binding rides the community-side
 * generalization (the CR-recorded widening of the existing
 * {@code NeighborhoodMembership} machine), never a copy here.</p>
 */
@Service
@Transactional
public class InstitutionService {

    /**
     * The geo port's own level contract (GeoNode's javadoc): 3 =
     * neighborhood — the same single administrative hierarchy the
     * membership's own anchor rides (D-N2; an int constant, not the geo
     * module's enum — the cross-module vocabulary IS the port's int).
     */
    static final int NEIGHBORHOOD_LEVEL = 3;

    private final InstitutionRepository repository;
    private final GeoLookupPort geoLookupPort;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public InstitutionService(InstitutionRepository repository,
                              GeoLookupPort geoLookupPort,
                              CurrentUserProvider currentUserProvider,
                              Clock clock) {
        this.repository = repository;
        this.geoLookupPort = geoLookupPort;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    /**
     * The registration: the caller IS the representative ({@code users.id}
     * at the A1 seam). The geo gate runs FIRST — an unknown node is the
     * port's own 404, a non-level-3 node answers 400 BEFORE any write.
     * The institution is born UNVERIFIED (the honest registry: visible
     * with its state; the trust mark arrives only through the review).
     */
    @Observed(name = "institution.register")
    public Institution register(InstitutionRequest request, Authentication authentication) {
        UUID representativeId = currentUserProvider.getCurrentUserId(authentication);
        GeoLookupPort.GeoNode node = geoLookupPort.getLocation(request.locationId());
        if (node.level() != NEIGHBORHOOD_LEVEL) {
            throw new BadRequestException(
                    "locationId must reference a level-3 neighborhood node, got level "
                            + node.level() + " (" + node.slug() + ")");
        }
        return repository.save(Institution.register(
                request.name(), request.type(), representativeId, request.locationId(),
                request.address(), request.phone(), request.website(), request.description(),
                clock));
    }

    /**
     * The public board: every verification state (the state rides the
     * response — the trust signal visible, never hidden), the type and
     * state axes optional. The service passes the stable
     * {@code (created_at, id)} sort (the L32/D-N5 lesson).
     */
    @Transactional(readOnly = true)
    public Page<Institution> searchBoard(InstitutionType type,
                                         InstitutionVerificationState state,
                                         Pageable pageable) {
        return repository.searchBoard(type, state, pageable);
    }

    /** The detail read: any live registry entry by id — the state honest, unknown 404. */
    @Transactional(readOnly = true)
    public Institution getInstitution(UUID institutionId) {
        return repository.findById(institutionId)
                .orElseThrow(() -> new ResourceNotFoundException("Institution not found: " + institutionId));
    }

    /** The representative's own registry — their managed institutions. */
    @Transactional(readOnly = true)
    public Page<Institution> myInstitutions(Authentication authentication, Pageable pageable) {
        UUID representativeId = currentUserProvider.getCurrentUserId(authentication);
        return repository.findByRepresentativeId(representativeId, pageable);
    }

    /**
     * The representative's verification request: UNVERIFIED → PENDING
     * only (the {@code requestVerification} house discipline — a
     * REJECTED claim cannot self-reverse; the recovery lever is the
     * administrator's APPROVE). A foreign registry entry answers 404
     * (the caller's own resource or nothing — the inbox discipline).
     */
    @Observed(name = "institution.verification.request")
    public Institution requestVerification(UUID institutionId, Authentication authentication) {
        UUID representativeId = currentUserProvider.getCurrentUserId(authentication);
        Institution institution = repository.findById(institutionId)
                .filter(i -> i.getRepresentativeId().equals(representativeId))
                .orElseThrow(() -> new ResourceNotFoundException("Institution not found: " + institutionId));
        institution.requestVerification();
        return institution;
    }

    /**
     * The review queue (administrative): the optional state axis
     * (PENDING the reviewable queue on its complete drain order —
     * updatedAt ASC, id ASC, oldest pending claim first — the house's
     * own {@code VERIFICATION_QUEUE_SORT} discipline), absent = the
     * whole registry.
     */
    private static final Sort VERIFICATION_QUEUE_SORT =
            Sort.by(Sort.Direction.ASC, "updatedAt").and(Sort.by(Sort.Direction.ASC, "id"));

    @Transactional(readOnly = true)
    public Page<Institution> reviewQueue(InstitutionVerificationState state, Pageable pageable) {
        Pageable queuePageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), VERIFICATION_QUEUE_SORT);
        return state == null
                ? repository.findAll(queuePageable)
                : repository.findByVerificationState(state, queuePageable);
    }

    /**
     * The verdict's ONLY mover (the
     * {@code NeighborhoodVerificationAdminController} discipline): APPROVE
     * moves a PENDING institution to VERIFIED and RE-ADMITS a REJECTED
     * one (the recovery lever); REJECT refuses a PENDING claim. Any
     * other source answers 409 with the entity's own transition words.
     */
    @Observed(name = "institution.verification.review")
    public Institution review(UUID institutionId, boolean approve) {
        Institution institution = repository.findById(institutionId)
                .orElseThrow(() -> new ResourceNotFoundException("Institution not found: " + institutionId));
        try {
            if (approve) {
                institution.approveVerification();
            } else {
                institution.rejectVerification();
            }
        } catch (IllegalStateException e) {
            throw new com.marketplace.shared.api.ConflictException(e.getMessage());
        }
        return institution;
    }

    /**
     * The administrative chain for the detail read's JSON-LD block: the
     * geo node's {@code parentId} walked to the root through the port
     * (one {@code getLocation} per level — the measured tree facts; the
     * L30 level mapping consumes exactly these). Package-private: the
     * controller's detail read is the only caller.
     */
    @Transactional(readOnly = true)
    java.util.List<GeoLookupPort.GeoNode> resolveChain(UUID locationId) {
        java.util.List<GeoLookupPort.GeoNode> chain = new java.util.ArrayList<>();
        GeoLookupPort.GeoNode current = geoLookupPort.getLocation(locationId);
        while (current != null) {
            chain.add(current);
            current = current.parentId() == null ? null : geoLookupPort.getLocation(current.parentId());
        }
        return chain;
    }
}
