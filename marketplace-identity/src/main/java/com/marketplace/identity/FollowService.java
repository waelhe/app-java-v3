package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.FollowedSourcesPort;
import com.marketplace.shared.api.GroupLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * JT-20 (the discovery waves — AC-20-05): the generalized follow domain —
 * the /me CRUD for USER and GROUP follows and the
 * {@code FollowedSourcesPort} union body.
 *
 * <p><b>The write path (the {@code ProviderFollowService} gates, ordered
 * and measured):</b> the self-follow pair answers 400 (the W4 "not the
 * provider itself" precedent — a member never follows himself), the
 * source's liveness is checked before the row is born (USER: the
 * module's OWN UserRepository — identity owns the users table; GROUP:
 * the shared {@link GroupLookupPort} seam — community owns the group
 * rows), and a live replay answers the STANDING row — the idempotent
 * contract this surface registered (deliberately unlike the provider
 * follow's explicit 409: this POST is "make sure I follow X", so a
 * client retry cannot split a hair). V177's partial unique index
 * {@code uq_follows_one_live} is the concurrent-insert backstop — the
 * race answers the DB's 23505 (the V93 two-layer model verbatim; the
 * explicit read keeps the common path constraint-free).
 *
 * <p><b>The withdraw:</b> an owner-scoped soft delete by (type, source)
 * pair, IDEMPOTENT — a pair you do not hold is a quiet no-op, never a
 * 404 (the DELETE's contract is "make sure I don't follow X"). The soft
 * delete frees the (member, type, source) triple, so the re-follow
 * inserts a fresh row (the b-5 house withdraw form; the withdrawn row
 * stays, the member's own record).
 *
 * <p><b>The union (the {@code FollowedSourcesPort} body — the port's
 * one-home rule):</b> the provider follow keeps its V93 home (its rows
 * never move, its write path never doubles), so the honest set is TWO
 * live queries unioned in memory: {@code follows} (USER + GROUP) and
 * {@code provider_follows} (as PROVIDER, in the provider USER-id space
 * the {@code ListingActivatedEvent} carries — the port's own contract).
 * The {@code ProviderFollow} getters are package-private, so the union
 * body lives HERE in the entity's own package and the spi adapter
 * delegates (the service composes; the HTTP/spi boundaries speak the
 * DTOs — the house rule).
 */
@Service
@Transactional
public class FollowService {

    private static final Logger log = LoggerFactory.getLogger(FollowService.class);

    /**
     * The port's closed PROVIDER vocabulary — the one string the union
     * adds on top of this module's enum (the PROVIDER home is V93's
     * table, deliberately outside {@link FollowableType}).
     */
    static final String PROVIDER_SOURCE_TYPE = "PROVIDER";

    private final FollowRepository repository;
    private final ProviderFollowRepository providerFollowRepository;
    private final UserRepository userRepository;
    private final GroupLookupPort groupLookupPort;

    public FollowService(FollowRepository repository,
                         ProviderFollowRepository providerFollowRepository,
                         UserRepository userRepository,
                         GroupLookupPort groupLookupPort) {
        this.repository = repository;
        this.providerFollowRepository = providerFollowRepository;
        this.userRepository = userRepository;
        this.groupLookupPort = groupLookupPort;
    }

    /**
     * The write's outcome — the composed view plus whether THIS call
     * created the row (the idempotent replay answers {@code created =
     * false}; the controller answers 201 on a fresh create, 200 on a
     * replay — the honest status pair for an idempotent POST).
     */
    public record FollowWriteResult(FollowView view, boolean created) {
    }

    // ------------------------------------------------------------------
    // The /me writes
    // ------------------------------------------------------------------

    /**
     * Follow a member. The self-pair is the honest 400; the target must
     * be a LIVE users row (the {@code @SoftDelete} filter keeps the
     * withdrawn out of {@code existsById} itself); a live replay answers
     * the standing row. A pseudonymized (former-member) target is NOT
     * separately gated — the row persists (I7's Art. 17(3)(b) decision)
     * and the neutral "former member" rendering stays the reader's own
     * decision, exactly as the I7 read-DTO contract fixed it.
     */
    @Observed(name = "follow.user.create")
    public FollowWriteResult followUser(UUID userId, UUID targetUserId) {
        if (targetUserId.equals(userId)) {
            throw new BadRequestException("You cannot follow yourself");
        }
        if (!userRepository.existsById(targetUserId)) {
            throw new ResourceNotFoundException("User not found: " + targetUserId);
        }
        return viewOrSave(userId, FollowableType.USER, targetUserId);
    }

    /** The USER withdraw — idempotent by contract (see the class javadoc). */
    @Observed(name = "follow.user.delete")
    public void unfollowUser(UUID userId, UUID targetUserId) {
        withdraw(userId, FollowableType.USER, targetUserId);
    }

    /**
     * Follow a neighborhood group. The group's liveness is checked
     * through the shared {@link GroupLookupPort} seam (community owns
     * the rows — the {@code PostLookupPort} pattern: the interface in
     * shared-api, the data owner implements it, this consumer injects
     * it); a live replay answers the standing row. There is NO
     * self-follow gate: the groups carry no owner column yet (V91's
     * documented product decision — the clubs are seed-authored), so
     * there is no "own group" pair to reject.
     */
    @Observed(name = "follow.group.create")
    public FollowWriteResult followGroup(UUID userId, UUID groupId) {
        if (!groupLookupPort.exists(groupId)) {
            throw new ResourceNotFoundException("Group not found: " + groupId);
        }
        return viewOrSave(userId, FollowableType.GROUP, groupId);
    }

    /** The GROUP withdraw — idempotent by contract (see the class javadoc). */
    @Observed(name = "follow.group.delete")
    public void unfollowGroup(UUID userId, UUID groupId) {
        withdraw(userId, FollowableType.GROUP, groupId);
    }

    // ------------------------------------------------------------------
    // The /me reads
    // ------------------------------------------------------------------

    /**
     * The member's own follows — newest first (the L32 deterministic
     * order), one type or the whole USER/GROUP store.
     */
    @Transactional(readOnly = true)
    public List<FollowView> listFollows(UUID userId, FollowableType type) {
        List<Follow> follows = (type == null)
                ? repository.findByUserIdOrderByCreatedAtDescIdDesc(userId)
                : repository.findByUserIdAndFollowableTypeOrderByCreatedAtDescIdDesc(userId, type);
        return follows.stream().map(FollowView::of).toList();
    }

    // ------------------------------------------------------------------
    // The FollowedSourcesPort union body (the port's one-home rule)
    // ------------------------------------------------------------------

    /**
     * The {@code FollowedSourcesPort} body — the honest union of the two
     * follow homes: {@code follows} (USER + GROUP, V177's table) and
     * {@code provider_follows} (as PROVIDER — the V93 rows in the
     * provider USER-id space the port's contract pins). Two live
     * queries, one in-memory union — never a dual write, never a
     * migration (the port's javadoc verbatim). The insertion order is
     * the member's own deterministic one (the follows newest-first,
     * then the provider leg) — a Set by contract, deterministic by
     * construction.
     */
    @Transactional(readOnly = true)
    public Set<FollowedSourcesPort.FollowedSource> followedSources(UUID userId) {
        Set<FollowedSourcesPort.FollowedSource> sources = new LinkedHashSet<>();
        for (Follow follow : repository.findByUserIdOrderByCreatedAtDescIdDesc(userId)) {
            sources.add(new FollowedSourcesPort.FollowedSource(
                    follow.getFollowableType().name(), follow.getFollowableId()));
        }
        for (ProviderFollow follow : providerFollowRepository
                .findByUserIdOrderByCreatedAtDescIdDesc(userId, Pageable.unpaged())) {
            sources.add(new FollowedSourcesPort.FollowedSource(
                    PROVIDER_SOURCE_TYPE, follow.getProviderUserId()));
        }
        log.debug("Followed-sources union: userId={}, sources={}", userId, sources.size());
        return sources;
    }

    // ------------------------------------------------------------------
    // The shared mechanics
    // ------------------------------------------------------------------

    /** The idempotent create: a live replay answers the standing row. */
    private FollowWriteResult viewOrSave(UUID userId, FollowableType type, UUID sourceId) {
        Follow existing = repository
                .findByUserIdAndFollowableTypeAndFollowableId(userId, type, sourceId)
                .orElse(null);
        if (existing != null) {
            return new FollowWriteResult(FollowView.of(existing), false);
        }
        return new FollowWriteResult(
                FollowView.of(repository.save(Follow.create(UUID.randomUUID(), userId, type, sourceId))),
                true);
    }

    /** The idempotent withdraw: a pair you do not hold is a quiet no-op. */
    private void withdraw(UUID userId, FollowableType type, UUID sourceId) {
        repository.findByUserIdAndFollowableTypeAndFollowableId(userId, type, sourceId)
                .ifPresent(repository::delete);
    }
}
