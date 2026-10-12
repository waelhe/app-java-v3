package com.marketplace.search.spi;

import com.marketplace.shared.api.CommunityDiscoveryPort;
import com.marketplace.shared.api.DiscoveryCardView;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.SearchCriteria;
import com.marketplace.shared.api.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 5 (the plan §5.1/§5.2 — «توسيع البحث الموحد لمصادر جديدة فوق العقد
 * القائم»): the unified search's first additional SOURCE leg beyond the
 * listings orchestration — the community domain (neighborhood posts and
 * events), bridged through the community module's PUBLIC projection port.
 *
 * <p><b>The source, measured before wiring (the §5.1 admission test):</b>
 * the source record is the community module's {@code neighborhood_posts} /
 * {@code neighborhood_events} rows; its OWNER is marketplace-community
 * (this adapter touches NO community repository, entity or table — the
 * Modulith boundary: everything crosses through
 * {@link CommunityDiscoveryPort}, the shared-api contract whose adapter
 * lives in the owning module); its STATE and eligibility travel on every
 * card and are RE-CHECKED here at hydration time; and its FAILURE is
 * measured and bounded below.</b>
 *
 * <p><b>The eligibility re-check (§5.1: «مرشحات الأهلية تفرض في مسار المجال
 * وتعاد فحوصتها»):</b> the port's adapter already applies the domain's
 * deterministic gates (VISIBLE-only, active lost-and-found, upcoming
 * events, the caller's scope). This adapter does NOT trust that answer —
 * before any card may reach a caller or an AI context it re-verifies, per
 * card: the post's moderation status is {@code VISIBLE}, a
 * {@code LOST_FOUND} report's lifecycle state is {@code ACTIVE}, an
 * event's state is neither {@code CANCELLED} nor {@code POSTPONED}, and
 * the card's scope location is the EXACT neighborhood the caller asked
 * for. A stale or foreign card is dropped here, at the seam, never
 * surfaced.</p>
 *
 * <p><b>The ambiguous-location law (§5.1: «الموقع الغامض يبقى سؤال توضيح
 * لا توسيعًا صامتًا»):</b> community sources are level-3 scoped — a search
 * WITHOUT an explicit geo scope answers an honest empty leg. The absent
 * location never widens this source into a national (location-less) read;
 * name resolution and candidate clarification live on the caller-facing
 * surfaces (the REST criteria carry an explicit id; the AI tool returns
 * {@code locationOptions} for an ambiguous name — C-5's equivalence
 * contract).</p>
 *
 * <p><b>The safe failure (§9.4):</b> a disabled source module (no
 * {@link CommunityDiscoveryPort} bean — {@link ObjectProvider}), an
 * unavailable source (the shared {@link ServiceUnavailableException}) or a
 * source-side persistence failure ({@link DataAccessException}) degrades
 * THIS leg to an honest empty answer with a logged reason — the unified
 * search never fabricates community cards, never fails the whole answer
 * because one leg is dark, and never converts a source outage into
 * invented results. The leg's {@link CommunityLegAnswer#sourceAvailable()}
 * tells the truth about which of the two empty shapes the caller got.</p>
 *
 * <p><b>No text leg, deliberately (§5.1: «من دون كائن معايير عملاق بحقول
 * بلا معنى لكل مجال»):</b> this leg consumes ONLY the criteria field the
 * community domain speaks — the explicit {@code locationId} — plus the
 * page bound. The neighborhood posts' Arabic full-text search is the
 * community module's own surface (its FTS/trigram repository, measured by
 * the pinned Arabic reference corpus); bridging it in without its own
 * contract would be a silent cross-module re-implementation, exactly what
 * the plan forbids.</p>
 */
@Component
public class CommunityDiscoverySearchAdapter {

    private static final Logger log = LoggerFactory.getLogger(CommunityDiscoverySearchAdapter.class);

    /** The closed card sourceType vocabulary ({@code DiscoveryCardView}'s contract). */
    static final String SOURCE_NEIGHBORHOOD_POST = "NEIGHBORHOOD_POST";
    static final String SOURCE_NEIGHBORHOOD_EVENT = "NEIGHBORHOOD_EVENT";

    // The owning module's eligibility vocabularies (PostStatus,
    // LostFoundState, NeighborhoodEventStatus) — mirrored as the card's
    // string contract; the adapter re-checks them instead of trusting them.
    private static final String POST_STATUS_VISIBLE = "VISIBLE";
    private static final String LOST_FOUND_STATE_ACTIVE = "ACTIVE";
    private static final Set<String> EVENT_INELIGIBLE_STATES = Set.of("CANCELLED", "POSTPONED");

    private final ObjectProvider<CommunityDiscoveryPort> communityDiscoveryPort;

    public CommunityDiscoverySearchAdapter(ObjectProvider<CommunityDiscoveryPort> communityDiscoveryPort) {
        this.communityDiscoveryPort = Objects.requireNonNull(
                communityDiscoveryPort, "communityDiscoveryPort must not be null");
    }

    /**
     * The community leg of a unified search: the eligible, exactly-scoped
     * cards of the caller's explicit neighborhood, deduped by the
     * (sourceType, sourceId) pair (AC-20-03), each carrying its source
     * identity, state and version moment. An empty {@code cards} list with
     * {@code sourceAvailable=true} is a genuinely empty neighborhood leg;
     * the same with {@code false} is a degraded leg (disabled or failed
     * source) — never a fabricated one.
     */
    public CommunityLegAnswer search(SearchCriteria criteria, PagedRequest perSourcePage) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        Objects.requireNonNull(perSourcePage, "perSourcePage must not be null");

        // §5.1: the absent scope is an honest empty leg — never a silent
        // widening to a national read.
        UUID scope = criteria.locationId();
        if (scope == null) {
            return CommunityLegAnswer.emptyWithSourceAvailable();
        }

        // §9.4: a disabled source module is an honest empty leg, not an
        // error the whole answer dies on.
        CommunityDiscoveryPort port = communityDiscoveryPort.getIfAvailable();
        if (port == null) {
            return CommunityLegAnswer.degraded("community discovery source is not wired");
        }

        try {
            List<DiscoveryCardView> hydrated = new java.util.ArrayList<>();
            for (CommunityDiscoveryPort.DiscoveryPostCard post
                    : port.findActiveLostFound(scope, perSourcePage).content()) {
                if (postEligible(post, scope)) {
                    hydrated.add(postCard(post));
                }
            }
            for (CommunityDiscoveryPort.DiscoveryPostCard post
                    : port.findRecommendations(scope, perSourcePage).content()) {
                if (postEligible(post, scope)) {
                    hydrated.add(postCard(post));
                }
            }
            for (CommunityDiscoveryPort.DiscoveryEventCard event
                    : port.findUpcomingEvents(scope, perSourcePage).content()) {
                if (eventEligible(event, scope)) {
                    hydrated.add(eventCard(event));
                }
            }
            return new CommunityLegAnswer(dedup(hydrated), true);
        } catch (ServiceUnavailableException | DataAccessException failure) {
            // §9.4: the deterministic functions degrade, they never invent.
            log.warn("Community search leg degraded: locationId={}, reason={}",
                    scope, failure.getMessage());
            return CommunityLegAnswer.degraded("community discovery source failed: "
                    + failure.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // The hydration-time eligibility re-check (§5.1)
    // ------------------------------------------------------------------

    private boolean postEligible(CommunityDiscoveryPort.DiscoveryPostCard post, UUID scope) {
        if (!POST_STATUS_VISIBLE.equals(post.status())) {
            return false; // a moderated post never reaches a caller or an AI context
        }
        if (post.lostFoundState() != null && !LOST_FOUND_STATE_ACTIVE.equals(post.lostFoundState())) {
            return false; // a resolved/recovered report never renders as active
        }
        return scope.equals(post.locationId()); // the exact scope — a foreign card is dropped
    }

    private boolean eventEligible(CommunityDiscoveryPort.DiscoveryEventCard event, UUID scope) {
        if (EVENT_INELIGIBLE_STATES.contains(event.status())) {
            return false; // CANCELLED/POSTPONED never masquerade as upcoming
        }
        return scope.equals(event.locationId());
    }

    // ------------------------------------------------------------------
    // The projection mappers (the discovery module's own shape verbatim —
    // the source's own facts, nothing invented)
    // ------------------------------------------------------------------

    private DiscoveryCardView postCard(CommunityDiscoveryPort.DiscoveryPostCard post) {
        return new DiscoveryCardView(SOURCE_NEIGHBORHOOD_POST, post.postId(), post.updatedAt(),
                post.title(), post.body(),
                post.lostFoundState() != null ? post.lostFoundState() : post.status(),
                post.locationId(), null, null);
    }

    private DiscoveryCardView eventCard(CommunityDiscoveryPort.DiscoveryEventCard event) {
        return new DiscoveryCardView(SOURCE_NEIGHBORHOOD_EVENT, event.eventId(), event.updatedAt(),
                event.title(), event.description(), event.status(),
                event.locationId(), null, null);
    }

    /** AC-20-03: the first occurrence of every (sourceType, sourceId) pair survives, in encounter order. */
    private static List<DiscoveryCardView> dedup(List<DiscoveryCardView> cards) {
        Map<String, DiscoveryCardView> unique = new LinkedHashMap<>(cards.size());
        for (DiscoveryCardView card : cards) {
            unique.putIfAbsent(card.sourceType() + ":" + card.sourceId(), card);
        }
        return List.copyOf(unique.values());
    }

    /**
     * The community leg's honest answer shape: the hydrated eligible cards
     * plus the truth about the source's availability — so an empty leg can
     * never be mistaken for a dead one, and a dead one never for an empty
     * one (§9.4's fail-safe is observable, not silent).
     */
    public record CommunityLegAnswer(List<DiscoveryCardView> cards, boolean sourceAvailable) {

        public CommunityLegAnswer {
            cards = cards == null ? List.of() : List.copyOf(cards);
        }

        /** The source answered: what it said is genuinely all it has in scope. */
        static CommunityLegAnswer emptyWithSourceAvailable() {
            return new CommunityLegAnswer(List.of(), true);
        }

        /** The source is disabled or failed: the leg is dark, honestly labelled. */
        static CommunityLegAnswer degraded(String reason) {
            log.debug("Community leg degraded: {}", reason);
            return new CommunityLegAnswer(List.of(), false);
        }
    }
}
