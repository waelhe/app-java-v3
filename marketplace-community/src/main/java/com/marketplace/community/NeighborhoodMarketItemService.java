package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.Currencies;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The neighborhood market board surface (L50 — the Nextdoor-2026
 * completeness wave, gap #5). One gate order, two commands, one read —
 * all the house precedents, measured:
 *
 * <p><b>The read gate (G-N3's default, verbatim from the feed and the
 * events board):</b> the market is for ACTIVE members of the
 * neighborhood — an authenticated caller with no active membership
 * answers the explicit 403, never an empty 200 that pretends the
 * board exists. ANY verification state reads (D-N3: REJECTED blocks
 * community writes, not the board).
 *
 * <p><b>The publish gate order (L41's own discipline, verbatim from
 * the post publish and the event organize):</b> the location is
 * resolved through {@link GeoLookupPort} FIRST — an unknown node is
 * the port's own 404 — then the level-3 requirement answers 400
 * BEFORE any write, then the active-membership match (403 — a
 * REJECTED member cannot publish, the write gate), and only then the
 * item's own ONE pricing rule (400 — «مجاني ⇔ بلا سعر»: a FREE item
 * carries no price at all, the four sale categories carry a strictly
 * positive integer-cents amount in ISO 4217; the V90 CHECK is the
 * backstop).
 *
 * <p><b>The withdraw gate (the organizer delete's own shape):</b> an
 * unknown or already-withdrawn item answers the honest 404; anyone
 * but the author answers 403; the withdraw itself is the house soft
 * delete — the row stays (b-5's retention, the Envers trail keeps
 * every revision), the reads stop returning it.
 *
 * <p><b>The badge batch (the L47/L49 grouped-read shape verbatim):</b>
 * the board's seller badges («جار موثق») come from ONE
 * {@code findByUserIdIn} read over the page's author ids — earned
 * verification states, never claimed ones, and SCOPED TO THIS BOARD'S
 * neighborhood (the review round's root fix: an author who left for
 * another neighborhood — even a VERIFIED one — keeps their items
 * readable here, but the badge does not follow them out; the javadoc's
 * own law, now enforced by the filter). An author who left with no
 * membership row at all renders the honest unverified floor. The empty
 * page short-circuits and costs no read.
 *
 * <p><b>Deterministic pagination (D-N5):</b> the board read forces the
 * complete sort key — {@code created_at DESC, id DESC} — so two items
 * landing in the same second never shake a page boundary (the board
 * is newest-first, the feed's own key — unlike the events board's
 * forward-looking time key).
 */
@Service
@Transactional
public class NeighborhoodMarketItemService {

    /**
     * The geo port's own level contract (GeoNode's javadoc): 3 =
     * neighborhood. The same constant the membership, post and event
     * services gate on — one vocabulary, the port's int.
     */
    static final int NEIGHBORHOOD_LEVEL = 3;

    /** The board's complete sort key (D-N5) — newest first, id breaking ties. */
    private static final Sort BOARD_SORT =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final NeighborhoodMarketItemRepository itemRepository;
    private final NeighborhoodMembershipRepository membershipRepository;
    private final GeoLookupPort geoLookupPort;

    public NeighborhoodMarketItemService(NeighborhoodMarketItemRepository itemRepository,
                                         NeighborhoodMembershipRepository membershipRepository,
                                         GeoLookupPort geoLookupPort) {
        this.itemRepository = itemRepository;
        this.membershipRepository = membershipRepository;
        this.geoLookupPort = geoLookupPort;
    }

    /**
     * Publish an item — the caller writes into their own active
     * neighborhood. The gate order is the post publish's own: port
     * resolve (404) → level-3 (400) → active membership in exactly
     * that location (403 — REJECTED included, the write gate) → the
     * ONE pricing rule (400 before any write) → insert. A member of a
     * DIFFERENT neighborhood answering this neighborhood's id is the
     * same 403 — G-N1's one-membership default means the board you
     * read is the board you write.
     */
    @Observed(name = "community.market.create")
    public NeighborhoodMarketItemView createItem(UUID authorId, UUID locationId,
                                                 MarketCategory category, String title,
                                                 MarketCondition condition,
                                                 Integer priceCents, String priceCurrency,
                                                 String locationLabel) {
        GeoLookupPort.GeoNode node = geoLookupPort.getLocation(locationId);
        if (node.level() != NEIGHBORHOOD_LEVEL) {
            throw new BadRequestException(
                    "locationId must reference a level-3 neighborhood node, got level "
                            + node.level() + " (" + node.slug() + ")");
        }
        NeighborhoodMembership membership = requireWritableMembershipIn(authorId, locationId,
                "Join a neighborhood before publishing market items (PUT /api/v1/me/neighborhood)",
                "Market items go to your own neighborhood — this location is not it");
        String normalizedCurrency = requireCoherentPricing(category, priceCents, priceCurrency);
        NeighborhoodMarketItem saved = itemRepository.save(NeighborhoodMarketItem.marketItem(
                authorId, locationId, category, title, condition,
                priceCents, normalizedCurrency, locationLabel));
        // The honest echo: the badge rides the membership the gate
        // itself already read — never a guessed state.
        return NeighborhoodMarketItemView.of(saved, isVerified(membership));
    }

    /**
     * The board — the caller's OWN neighborhood's items, newest first,
     * on the complete sort key. The filter axes are the product's own:
     * {@code category} (the chips), {@code query} (the search box —
     * title + pickup-spot label), {@code mine} (the member's
     * own-items view). No membership ⇒ the explicit 403 (G-N3's
     * default) — there is no location parameter to read anyone
     * else's board: the membership IS the scope. ANY verification
     * state reads (D-N3: REJECTED blocks community writes, not the
     * board).
     */
    @Transactional(readOnly = true)
    public Page<NeighborhoodMarketItemView> getBoard(UUID callerId, MarketCategory category,
                                                     String query, boolean mine,
                                                     Pageable pageable) {
        UUID locationId = requireMembership(callerId,
                "Join a neighborhood before reading its market board (PUT /api/v1/me/neighborhood)")
                .getLocationId();
        Pageable boardPageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), BOARD_SORT);
        Page<NeighborhoodMarketItem> page = itemRepository.findAll(
                NeighborhoodMarketItemSpecifications.hasLocation(locationId)
                        .and(NeighborhoodMarketItemSpecifications.textMatches(query))
                        .and(NeighborhoodMarketItemSpecifications.hasCategory(category))
                        .and(NeighborhoodMarketItemSpecifications.onlyMine(mine, callerId)),
                boardPageable);
        // The board read carries the two caller-scoped facts — the
        // grouped seller-badge batch over the page's author ids and
        // the authorship comparison (the L47/L49 pattern verbatim:
        // one grouped read over the page's ids; the empty page
        // short-circuits below and costs nothing).
        List<NeighborhoodMarketItem> items = page.getContent();
        Map<UUID, Boolean> badges = sellerBadges(items, locationId);
        return page.map(item -> NeighborhoodMarketItemView.of(
                item,
                badges.getOrDefault(item.getAuthorId(), false),
                item.getAuthorId().equals(callerId)));
    }

    /**
     * The author's own withdraw — the house soft delete. The row stays
     * (b-5's retention — the Envers trail keeps every revision), the
     * reads stop returning it. Only the author: anyone else answers
     * 403; an unknown item answers the honest 404.
     */
    @Observed(name = "community.market.delete")
    public void deleteByAuthor(UUID authorId, UUID itemId) {
        NeighborhoodMarketItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Market item", itemId));
        if (!item.getAuthorId().equals(authorId)) {
            throw new AccessDeniedException("Only the item's author can withdraw it");
        }
        itemRepository.delete(item);
    }

    /**
     * The ONE pricing rule (the design's own binary model): a FREE
     * item is a gift — no price at all («مجاني ⇔ بلا سعر», the safety
     * rules' own «الإعلانات المجانية بلا مقابل»); the four sale
     * categories carry a strictly positive integer-cents amount in a
     * 3-letter ISO 4217 code (the V2 money shape). The friendly 400
     * here is the V90 CHECK's own twin — the constraint is the
     * backstop.
     *
     * <p>The currency's validity authority is the house helper
     * {@link Currencies#normalize(String)} — the JDK's own ISO 4217
     * table (the review round's root fix: the regex accepted any three
     * uppercase letters, so {@code ZZZ} passed both this gate and the
     * V90 backstop while violating the advertised contract). The same
     * call normalizes the stored form (trim/upper) — the stored code is
     * always the canonical uppercase the DB CHECK pins.
     */
    private String requireCoherentPricing(MarketCategory category, Integer priceCents,
                                           String priceCurrency) {
        if (category == MarketCategory.FREE) {
            if (priceCents != null || priceCurrency != null) {
                throw new BadRequestException(
                        "A FREE item is a gift — leave the price absent (مجاني ⇔ بلا سعر)");
            }
            return null;
        }
        if (priceCents == null || priceCents <= 0) {
            throw new BadRequestException(
                    "A " + category + " item requires a strictly positive price in integer cents");
        }
        if (priceCurrency == null || priceCurrency.isBlank()) {
            throw new BadRequestException(
                    "priceCurrency must be a 3-letter ISO 4217 code (e.g. SAR)");
        }
        try {
            return Currencies.normalize(priceCurrency);
        } catch (IllegalArgumentException unknownCode) {
            throw new BadRequestException(
                    "priceCurrency must be a 3-letter ISO 4217 code (e.g. SAR) — "
                            + priceCurrency + " is not one");
        }
    }

    /**
     * L50: the grouped seller-badge read over the page's author ids —
     * ONE membership read for the whole page (the L47/L49 grouped
     * shape verbatim); the empty page short-circuits to the empty map
     * (a closed board costs no read). An author with no ACTIVE
     * membership row renders the honest unverified floor — and so does
     * an author whose membership points at ANOTHER neighborhood now
     * (the review round's root fix): the badge is THIS board's earned
     * trust, so only a membership in the board's own location counts;
     * a left member's items stay readable, their badge does not follow
     * them out (the javadoc's own law, enforced).
     */
    private Map<UUID, Boolean> sellerBadges(List<NeighborhoodMarketItem> items, UUID locationId) {
        if (items.isEmpty()) {
            return Map.of();
        }
        Set<UUID> authorIds = items.stream()
                .map(NeighborhoodMarketItem::getAuthorId)
                .collect(Collectors.toSet());
        return membershipRepository.findByUserIdIn(authorIds).stream()
                .filter(membership -> membership.getLocationId().equals(locationId))
                .collect(Collectors.toMap(
                        NeighborhoodMembership::getUserId,
                        this::isVerified));
    }

    /** The badge's own law: VERIFIED is the earned state, everything else is not. */
    private boolean isVerified(NeighborhoodMembership membership) {
        return membership.getVerificationState() == MembershipVerificationState.VERIFIED;
    }

    /**
     * The caller's membership — ANY verification state reads (D-N3:
     * REJECTED blocks community writes, never the board). Absent
     * membership ⇒ the explicit 403.
     */
    private NeighborhoodMembership requireMembership(UUID callerId, String noMembershipMessage) {
        return membershipRepository.findByUserId(callerId)
                .orElseThrow(() -> new AccessDeniedException(noMembershipMessage));
    }

    /**
     * The caller's membership WITH the community-write right — a
     * REJECTED claim answers the explicit 403 (G-N3; the #461 round:
     * the write gate, not the shared existence gate, carries this
     * check).
     */
    private NeighborhoodMembership requireWritableMembership(UUID callerId, String noMembershipMessage) {
        NeighborhoodMembership membership = requireMembership(callerId, noMembershipMessage);
        if (!membership.mayUseCommunityWrites()) {
            throw new AccessDeniedException("Rejected neighborhood verification cannot publish, comment, or recommend");
        }
        return membership;
    }

    /**
     * The membership-in-location WRITE gate: absent membership ⇒ 403
     * with the join hint; a membership in a DIFFERENT neighborhood ⇒
     * 403 with the scope fact — both checks land before any write.
     */
    private NeighborhoodMembership requireWritableMembershipIn(UUID callerId, UUID locationId,
                                                               String noMembershipMessage,
                                                               String wrongLocationMessage) {
        NeighborhoodMembership membership =
                requireWritableMembership(callerId, noMembershipMessage);
        if (!membership.getLocationId().equals(locationId)) {
            throw new AccessDeniedException(wrongLocationMessage);
        }
        return membership;
    }
}
