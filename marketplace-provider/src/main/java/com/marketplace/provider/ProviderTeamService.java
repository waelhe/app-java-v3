package com.marketplace.provider;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * W5b (the journeys register's J29 row — تفويض فريق مزود الخدمة بصلاحيات
 * ونطاق): the provider team delegation engine — the register's own
 * CONFIRMED GAP wording: «تفويض فريق/فرع بصلاحيات ونطاق — غير منفذ ←
 * W5/W11», now built on the {@code UrgentAlertService} house shapes.
 *
 * <p><b>The delegation's own law (the owner is the only granter):</b> every
 * write on this surface — add, revoke, the roster read — resolves the
 * provider (404 for an unknown one) then checks the acting user IS the
 * owner ({@code provider_profiles.user_id}); anyone else answers 403,
 * admin included (the administrative suspension/verification surfaces are
 * the admin's own levers; the team is the owner's). The gate order runs
 * BEFORE any write (the {@code InstitutionService#register} discipline
 * verbatim): unknown provider 404 → non-owner 403 → member==owner 409 (the
 * owner's authority is the profile itself, a seat would be noise) →
 * duplicate live seat 409 → the role's vocabulary 400.
 *
 * <p><b>One live seat per member per provider (the V234 law):</b> the read
 * gate answers 409 BEFORE any write; the partial unique index
 * {@code uq_provider_team_one_live} is the CONCURRENT writer's backstop —
 * the add flow saves with {@code saveAndFlush} inside a try/catch that
 * translates the {@link DataIntegrityViolationException} into the same 409
 * the gate would have taught (the L31 unique-race shape). A REVOKED seat
 * never blocks a later re-add: the index holds over live rows only.
 *
 * <p><b>The authorization seam (the real J29 value — «موظف تجاوز نطاقه» is
 * the failure branch to prove):</b> {@link #isActingMember} and
 * {@link #isActiveEditorOrAbove} are the two questions every delegated
 * surface asks. Both answer {@code false} for an unknown provider or a
 * null user — an authorization question, not a lookup: silence is the
 * honest negative (a stranger's write keeps the existing 403 semantics
 * through the caller's own gate).
 *
 * <p><b>The owner-only surfaces stay owner-only BY DESIGN:</b> the private
 * fulfillment location (marketplace pickup point — sensitive data) and the
 * ownership-verification claim never accept a delegation; the gates live
 * where they always did ({@code ProviderService.verifyOwnership}), and
 * only the declared profile/business-page surfaces consult the seam.
 */
@Service
@Transactional
public class ProviderTeamService {

    private final ProviderRepository providerRepository;
    private final ProviderTeamMemberRepository teamRepository;

    public ProviderTeamService(ProviderRepository providerRepository,
                               ProviderTeamMemberRepository teamRepository) {
        this.providerRepository = providerRepository;
        this.teamRepository = teamRepository;
    }

    /**
     * The grant: the owner delegates one seat to one member — born ACTIVE
     * (the owner's add IS the grant; there is no pending state in the
     * first cut). The whole guard family runs BEFORE the write, in the
     * documented order.
     *
     * @param actor the explicit delegation attribution for {@code added_by}
     *              (the acting owner's resolved user id as text — the
     *              honest WHO, distinct from the auditing subject)
     */
    @Observed(name = "provider.team.add")
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderTeamMember addMember(UUID providerId, UUID actingUserId,
                                        UUID memberUserId, String role, String actor) {
        ProviderProfile provider = ownedProvider(providerId, actingUserId);
        if (memberUserId == null) {
            throw new BadRequestException("memberUserId is required");
        }
        if (memberUserId.equals(provider.getUserId())) {
            throw new ConflictException("The owner needs no delegation — the owner's "
                    + "authority is the profile itself: " + memberUserId);
        }
        if (teamRepository.existsByProviderIdAndMemberUserIdAndStatus(
                provider.getId(), memberUserId, ProviderTeamMemberStatus.ACTIVE)) {
            throw new ConflictException("Member already holds a live team seat on this "
                    + "provider: " + memberUserId);
        }
        ProviderTeamRole teamRole = parseRole(role);
        try {
            return teamRepository.saveAndFlush(ProviderTeamMember.create(
                    provider.getId(), memberUserId, teamRole, actor));
        } catch (DataIntegrityViolationException e) {
            // The partial unique index's race backstop: two concurrent adds
            // of the same member both passed the read gate — the index holds
            // the one-live-seat law, and the loser answers the same 409 the
            // gate would have taught (the L31 unique-race shape).
            throw new ConflictException("Member already holds a live team seat on this "
                    + "provider: " + memberUserId);
        }
    }

    /**
     * The withdrawal: the owner revokes one seat — ACTIVE → REVOKED, the
     * only legal exit (anything else answers the machine's own 409). The
     * row and its Envers trail stay; the authority ends at commit, so
     * every delegated write through the seam fails from the next request
     * on («موظف تجاوز نطاقه» proven by the journey test's final branch).
     */
    @Observed(name = "provider.team.revoke")
    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderTeamMember revoke(UUID providerId, UUID actingUserId, UUID memberId) {
        ProviderProfile provider = ownedProvider(providerId, actingUserId);
        ProviderTeamMember member = teamRepository.findById(memberId)
                .filter(m -> m.getProviderId().equals(provider.getId()))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Team member not found for this provider: " + memberId));
        member.revoke();
        return teamRepository.save(member);
    }

    /** The owner's roster read: the live seats in arrival order (the history lives in Envers). */
    @Observed(name = "provider.team.list")
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional(readOnly = true)
    public List<ProviderTeamMember> team(UUID providerId, UUID actingUserId) {
        ProviderProfile provider = ownedProvider(providerId, actingUserId);
        return teamRepository.findByProviderIdAndStatusOrderByCreatedAtAscIdAsc(
                provider.getId(), ProviderTeamMemberStatus.ACTIVE);
    }

    /**
     * The J29 authorization seam, acting-member floor: TRUE when the user
     * is the owner OR holds a live seat of ANY role. The question future
     * read surfaces (the W11 inbox family) ask — presence, not privilege.
     */
    @Transactional(readOnly = true)
    public boolean isActingMember(UUID providerId, UUID userId) {
        if (userId == null) {
            return false;
        }
        return isOwner(providerId, userId) || teamRepository.existsByProviderIdAndMemberUserIdAndStatus(
                providerId, userId, ProviderTeamMemberStatus.ACTIVE);
    }

    /**
     * The J29 authorization seam, write floor: TRUE when the user is the
     * owner OR holds a live seat with role MANAGER or EDITOR — the two
     * write-capable classes of the first cut. This is the gate the declared
     * profile/business-page surfaces consult ({@code ProviderService}
     * update and the business-page writes); a future read-only VIEWER
     * class falls through here WITHOUT touching a single gate.
     */
    @Transactional(readOnly = true)
    public boolean isActiveEditorOrAbove(UUID providerId, UUID userId) {
        if (userId == null) {
            return false;
        }
        if (isOwner(providerId, userId)) {
            return true;
        }
        return teamRepository.findByProviderIdAndMemberUserIdAndStatus(
                        providerId, userId, ProviderTeamMemberStatus.ACTIVE)
                .map(member -> member.getRole() == ProviderTeamRole.MANAGER
                        || member.getRole() == ProviderTeamRole.EDITOR)
                .orElse(false);
    }

    // -- helpers ----------------------------------------------------------------

    /**
     * The surface's own resolution: the provider must exist (404) and the
     * acting user must BE its owner (403 otherwise — no admin bypass on
     * the team surface; the admin's levers are suspension/verification).
     */
    private ProviderProfile ownedProvider(UUID providerId, UUID actingUserId) {
        ProviderProfile provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new ResourceNotFoundException("Provider not found: " + providerId));
        if (provider.getUserId() == null || !provider.getUserId().equals(actingUserId)) {
            throw new AccessDeniedException("Only the provider owner can manage the team");
        }
        return provider;
    }

    /**
     * The owner's own read: one profile lookup answering whether the user
     * IS the owner — {@code false} for an unknown provider (an
     * authorization question, not a lookup).
     */
    private boolean isOwner(UUID providerId, UUID userId) {
        return providerRepository.findById(providerId)
                .map(p -> p.getUserId() != null && p.getUserId().equals(userId))
                .orElse(false);
    }

    private static ProviderTeamRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            throw new BadRequestException("role is required — MANAGER or EDITOR");
        }
        try {
            return ProviderTeamRole.valueOf(role.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(
                    "role must be MANAGER or EDITOR — got: " + role.trim());
        }
    }
}
