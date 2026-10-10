package com.marketplace.discovery;

import com.marketplace.shared.api.CommunityDiscoveryPort;
import com.marketplace.shared.api.CommunityMembershipPort;
import com.marketplace.shared.api.DiscoveryCardView;
import com.marketplace.shared.api.DiscoveryRowType;
import com.marketplace.shared.api.DiscoveryRowView;
import com.marketplace.shared.api.FollowedSourcesPort;
import com.marketplace.shared.api.JobsDiscoveryPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.ProviderListingSummary;
import com.marketplace.shared.api.ProviderListingsPort;
import com.marketplace.shared.api.UrgentAlertsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Wave D-1 (plan #536 §1.4 / JT-20): the home discovery rails' engine —
 * the DETERMINISTIC assembler between the shared-api ports and the
 * {@link DiscoveryRowView} projection. Every eligibility, visibility and
 * validity decision is made inside the owning module's adapter (the ports
 * return visible, correctly-scoped records only — AC-20-01); this service
 * scopes the whole surface to the caller's active-membership neighborhood
 * through {@link CommunityMembershipPort#getActiveNeighborhoodId(UUID)}
 * (the membership IS the scope — no membership answers an honest empty
 * list, never a widened one, AC-02-02), converts the ports' cards to the
 * {@link DiscoveryCardView} projection with an HONEST reason per rail
 * (AC-20-06/AC-20-11), dedups each row by the (sourceType, sourceId) pair
 * (AC-20-03), and omits every empty row from the response entirely
 * (AC-20-07 — the empty state is the client's honest rendering of the
 * row's absence, never a fabricated placeholder).
 *
 * <p><b>The render order is the rail order §1.4 fixed</b> (the
 * {@link DiscoveryRowType} enum's own numbering): URGENT_ALERTS above the
 * ordinary rails (never ranked by popularity or a learned ordering,
 * AC-20-06), then FOLLOWED_SOURCES, LOST_FOUND,
 * NEIGHBORHOOD_RECOMMENDATIONS, EVENTS_AND_OPPORTUNITIES and FOR_YOU —
 * the explicit follow is never replaced by "for you" (AC-20-05), so
 * FOR_YOU's candidate pool excludes every followed source. No ML anywhere
 * (decision D-13): FOR_YOU is recency over the other rails' candidate
 * pools with the reason always present.</p>
 *
 * <p><b>Transactions:</b> the service carries none of its own — the row
 * assembly is a port orchestration (each adapter owns its module's
 * transaction) and the impression recording must stay OUTSIDE any
 * surrounding transaction so a ledger failure can never poison a shared
 * one: {@link #recordImpression} catches the failure and logs it — the
 * browse experience never breaks on an impression hiccup (the
 * 204-always contract); the ledger's own short transaction rides the
 * repository method's {@code @Transactional} (see
 * {@link DiscoveryImpressionRepository#insertOnce}).</p>
 */
@Service
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    /**
     * The aggregated home view's per-row card bound (the rail shows a
     * bounded page; the full filtered list lives on the row's own topic
     * page — {@code GET /discovery/rows/{row}}, UJ-261).
     */
    static final int HOME_ROW_CARD_LIMIT = 10;

    /** The closed card sourceType vocabulary (the {@code DiscoveryCardView} contract). */
    static final String SOURCE_NEIGHBORHOOD_POST = "NEIGHBORHOOD_POST";
    static final String SOURCE_NEIGHBORHOOD_EVENT = "NEIGHBORHOOD_EVENT";
    static final String SOURCE_JOB = "JOB";
    static final String SOURCE_URGENT_ALERT = "URGENT_ALERT";
    static final String SOURCE_PROVIDER_LISTING = "PROVIDER_LISTING";

    // The honest per-rail reasons (AC-20-06/AC-20-11 — the user can
    // understand and correct every card's presence; never empty on FOR_YOU).
    static final String REASON_URGENT_ALERTS = "تنبيه رسمي لحيّك";
    static final String REASON_FOLLOWED_SOURCES = "من مصادر أتابعها";
    static final String REASON_LOST_FOUND = "مفقودات نشطة في حيّك";
    static final String REASON_RECOMMENDATIONS = "توصية جيران في حيّك";
    static final String REASON_EVENT = "فعالية قريبة في حيّك";
    static final String REASON_JOB = "فرصة قريبة";
    static final String REASON_FOR_YOU = "حديث في حيّك";

    // The followed-source type vocabulary (the FollowedSourcesPort contract).
    private static final String FOLLOW_TYPE_USER = "USER";
    private static final String FOLLOW_TYPE_PROVIDER = "PROVIDER";

    // The aggregated home view's per-port window: one bounded page per
    // source, the row's card limit the cap after dedup and merge.
    private static final PagedRequest HOME_REQUEST = PagedRequest.of(0, HOME_ROW_CARD_LIMIT);

    private final CommunityMembershipPort membershipPort;
    private final CommunityDiscoveryPort communityPort;
    private final JobsDiscoveryPort jobsPort;
    private final UrgentAlertsPort urgentAlertsPort;
    private final FollowedSourcesPort followedSourcesPort;
    private final ProviderListingsPort providerListingsPort;
    private final DiscoveryImpressionRepository impressions;
    private final Clock clock;

    public DiscoveryService(CommunityMembershipPort membershipPort,
                            CommunityDiscoveryPort communityPort,
                            JobsDiscoveryPort jobsPort,
                            UrgentAlertsPort urgentAlertsPort,
                            FollowedSourcesPort followedSourcesPort,
                            ProviderListingsPort providerListingsPort,
                            DiscoveryImpressionRepository impressions,
                            Clock clock) {
        this.membershipPort = membershipPort;
        this.communityPort = communityPort;
        this.jobsPort = jobsPort;
        this.urgentAlertsPort = urgentAlertsPort;
        this.followedSourcesPort = followedSourcesPort;
        this.providerListingsPort = providerListingsPort;
        this.impressions = impressions;
        this.clock = clock;
    }

    /**
     * The aggregated home discovery: every rail assembled for the caller's
     * scope, in §1.4's rail order, each rail deduped by the (sourceType,
     * sourceId) pair (AC-20-03) and bounded at {@link #HOME_ROW_CARD_LIMIT}
     * cards — and every EMPTY rail ABSENT from the response entirely
     * (AC-20-07). No membership, no scope: the honest empty list (never a
     * silent widening to a city or national surface).
     */
    public List<DiscoveryRowView> homeRows(UUID userId) {
        Optional<UUID> neighborhood = membershipPort.getActiveNeighborhoodId(userId);
        if (neighborhood.isEmpty()) {
            return List.of();
        }
        UUID locationId = neighborhood.get();
        Instant now = clock.instant();

        FollowedSources followed = followedSources(userId);

        List<UrgentAlertsPort.UrgentAlertCard> alerts = urgentAlertsPort.findActive(locationId, now);
        PagedResponse<CommunityDiscoveryPort.DiscoveryPostCard> lostFound =
                communityPort.findActiveLostFound(locationId, HOME_REQUEST);
        PagedResponse<CommunityDiscoveryPort.DiscoveryPostCard> recommendations =
                communityPort.findRecommendations(locationId, HOME_REQUEST);
        PagedResponse<CommunityDiscoveryPort.DiscoveryEventCard> events =
                communityPort.findUpcomingEvents(locationId, HOME_REQUEST);
        PagedResponse<JobsDiscoveryPort.DiscoveryJobCard> jobs =
                jobsPort.findOpenJobs(locationId, HOME_REQUEST);
        PagedResponse<CommunityDiscoveryPort.DiscoveryPostCard> followedPosts =
                followed.authorIds().isEmpty()
                        ? PagedResponse.empty(HOME_REQUEST)
                        : communityPort.findRecentByAuthors(followed.authorIds(), HOME_REQUEST);
        PagedResponse<ProviderListingSummary> followedListings =
                followed.providerIds().isEmpty()
                        ? PagedResponse.empty(HOME_REQUEST)
                        : providerListingsPort.findActiveByProviders(followed.providerIds(), HOME_REQUEST);

        List<DiscoveryCardView> alertCards = alerts.stream()
                .map(alert -> new DiscoveryCardView(SOURCE_URGENT_ALERT, alert.alertId(),
                        alert.updatedAt(), alert.title(), alert.body(), null,
                        alert.locationId(), REASON_URGENT_ALERTS, null))
                .toList();
        List<DiscoveryCardView> lostFoundCards = lostFound.content().stream()
                .map(post -> postCard(post, REASON_LOST_FOUND))
                .toList();
        List<DiscoveryCardView> recommendationCards = recommendations.content().stream()
                .map(post -> postCard(post, REASON_RECOMMENDATIONS))
                .toList();
        List<DiscoveryCardView> opportunityCards = recencyOrdered(mergeCards(
                events.content().stream().map(event -> eventCard(event, REASON_EVENT)).toList(),
                jobs.content().stream().map(job -> jobCard(job, REASON_JOB)).toList()));
        List<DiscoveryCardView> followedCards = recencyOrdered(mergeCards(
                followedPosts.content().stream().map(post -> postCard(post, REASON_FOLLOWED_SOURCES)).toList(),
                followedListings.content().stream().map(listing -> listingCard(listing, REASON_FOLLOWED_SOURCES)).toList()));
        List<DiscoveryCardView> forYouCandidates = forYouCandidates(lostFound, recommendations, events, jobs, followed);

        List<DiscoveryRowView> rows = new ArrayList<>();
        // The delegated official alerts keep the adapter's own order (the
        // official surface's authority — never re-ranked here); the rail
        // bounds are the only shaping applied.
        addRowIfNonEmpty(rows, DiscoveryRowType.URGENT_ALERTS, bound(dedup(alertCards)), alerts.size());
        addRowIfNonEmpty(rows, DiscoveryRowType.FOLLOWED_SOURCES, bound(followedCards),
                followedPosts.totalElements() + followedListings.totalElements());
        addRowIfNonEmpty(rows, DiscoveryRowType.LOST_FOUND, bound(lostFoundCards), lostFound.totalElements());
        addRowIfNonEmpty(rows, DiscoveryRowType.NEIGHBORHOOD_RECOMMENDATIONS, bound(recommendationCards),
                recommendations.totalElements());
        addRowIfNonEmpty(rows, DiscoveryRowType.EVENTS_AND_OPPORTUNITIES, bound(opportunityCards),
                events.totalElements() + jobs.totalElements());
        // FOR_YOU's honest total is the measured candidate count outside
        // the follows (the source totals over-count by the followed share
        // the rail excludes by contract, AC-20-05).
        addRowIfNonEmpty(rows, DiscoveryRowType.FOR_YOU, bound(forYouCandidates), forYouCandidates.size());
        return List.copyOf(rows);
    }

    /**
     * One rail's topic page — "عرض الكل" (UJ-261/262): the FULL page the
     * owning port answers, converted to the card projection without the
     * home view's card bound (the bound is the aggregated view's own
     * shaping; the topic page paginates honestly through page/size).
     *
     * <p><b>The merged rails' page discipline</b> (EVENTS_AND_OPPORTUNITIES,
     * FOLLOWED_SOURCES, FOR_YOU — two or more paged sources under one rail):
     * each source answers its own (page, size) slice, the slices merge,
     * dedup by the (sourceType, sourceId) pair, order by recency
     * (updatedAt DESC, the sourceId tiebreaker) and bound at the requested
     * size — a deterministic window over the merged sources, with the
     * totalEligible the sources' honest sum. The delegated-alert rail has
     * no paging port: the bounded official list renders as one page.</p>
     */
    public PagedResponse<DiscoveryCardView> rowPage(UUID userId, DiscoveryRowType row, PagedRequest request) {
        Optional<UUID> neighborhood = membershipPort.getActiveNeighborhoodId(userId);
        if (neighborhood.isEmpty()) {
            return PagedResponse.empty(request);
        }
        UUID locationId = neighborhood.get();
        return switch (row) {
            case URGENT_ALERTS -> urgentAlertsPage(locationId, request);
            case FOLLOWED_SOURCES -> followedSourcesPage(userId, request);
            case LOST_FOUND -> communityPort.findActiveLostFound(locationId, request)
                    .map(post -> postCard(post, REASON_LOST_FOUND));
            case NEIGHBORHOOD_RECOMMENDATIONS -> communityPort.findRecommendations(locationId, request)
                    .map(post -> postCard(post, REASON_RECOMMENDATIONS));
            case EVENTS_AND_OPPORTUNITIES -> {
                PagedResponse<CommunityDiscoveryPort.DiscoveryEventCard> events =
                        communityPort.findUpcomingEvents(locationId, request);
                PagedResponse<JobsDiscoveryPort.DiscoveryJobCard> jobs =
                        jobsPort.findOpenJobs(locationId, request);
                yield mergedPage(recencyOrdered(mergeCards(
                                events.content().stream().map(event -> eventCard(event, REASON_EVENT)).toList(),
                                jobs.content().stream().map(job -> jobCard(job, REASON_JOB)).toList())),
                        request, events.totalElements() + jobs.totalElements());
            }
            case FOR_YOU -> forYouPage(locationId, userId, request);
        };
    }

    /**
     * Records one card impression on the {@code discovery_impressions}
     * ledger (V176) — the display day is today on the platform clock. The
     * repeated (user, row, source, day) pair is the ledger's own skip (a
     * returned 0 — the V93 bridge discipline, never a 23505); ANY other
     * recording failure is caught and logged, never rethrown: the browse
     * experience must not fail because bookkeeping did (the 204-always
     * contract). Value validation (the row/sourceType vocabularies, the
     * id's presence) happened at the controller's type gates BEFORE this
     * call — a 400 there, never a swallowed one here.
     */
    public void recordImpression(UUID userId, DiscoveryRowType row, String sourceType, UUID sourceId) {
        LocalDate impressionDay = LocalDate.now(clock);
        try {
            impressions.insertOnce(UUID.randomUUID(), userId, row.name(), sourceType, sourceId, impressionDay);
        } catch (RuntimeException failure) {
            log.warn("Discovery impression recording skipped (browse continues): userId={}, row={}, source={}/{}",
                    userId, row, sourceType, sourceId, failure);
        }
    }

    // ------------------------------------------------------------------
    // The rail assemblers
    // ------------------------------------------------------------------

    private PagedResponse<DiscoveryCardView> urgentAlertsPage(UUID locationId, PagedRequest request) {
        List<DiscoveryCardView> cards = urgentAlertsPort.findActive(locationId, clock.instant()).stream()
                .map(alert -> new DiscoveryCardView(SOURCE_URGENT_ALERT, alert.alertId(),
                        alert.updatedAt(), alert.title(), alert.body(), null,
                        alert.locationId(), REASON_URGENT_ALERTS, null))
                .toList();
        // The official surface is a bounded list, never a paged archive:
        // the whole active set renders as one page (page 0), the adapter's
        // own order preserved (never re-ranked, AC-20-06).
        return new PagedResponse<>(request.page() == 0 ? cards : List.of(),
                request.page(), request.size(), cards.size(), 1, true);
    }

    private PagedResponse<DiscoveryCardView> followedSourcesPage(UUID userId, PagedRequest request) {
        FollowedSources followed = followedSources(userId);
        if (followed.authorIds().isEmpty() && followed.providerIds().isEmpty()) {
            return PagedResponse.empty(request);
        }
        PagedResponse<CommunityDiscoveryPort.DiscoveryPostCard> posts = followed.authorIds().isEmpty()
                ? PagedResponse.empty(request)
                : communityPort.findRecentByAuthors(followed.authorIds(), request);
        PagedResponse<ProviderListingSummary> listings = followed.providerIds().isEmpty()
                ? PagedResponse.empty(request)
                : providerListingsPort.findActiveByProviders(followed.providerIds(), request);
        List<DiscoveryCardView> cards = recencyOrdered(mergeCards(
                posts.content().stream().map(post -> postCard(post, REASON_FOLLOWED_SOURCES)).toList(),
                listings.content().stream().map(listing -> listingCard(listing, REASON_FOLLOWED_SOURCES)).toList()));
        return mergedPage(cards, request, posts.totalElements() + listings.totalElements());
    }

    private PagedResponse<DiscoveryCardView> forYouPage(UUID locationId, UUID userId, PagedRequest request) {
        FollowedSources followed = followedSources(userId);
        PagedResponse<CommunityDiscoveryPort.DiscoveryPostCard> lostFound =
                communityPort.findActiveLostFound(locationId, request);
        PagedResponse<CommunityDiscoveryPort.DiscoveryPostCard> recommendations =
                communityPort.findRecommendations(locationId, request);
        PagedResponse<CommunityDiscoveryPort.DiscoveryEventCard> events =
                communityPort.findUpcomingEvents(locationId, request);
        PagedResponse<JobsDiscoveryPort.DiscoveryJobCard> jobs =
                jobsPort.findOpenJobs(locationId, request);
        List<DiscoveryCardView> candidates = forYouCandidates(lostFound, recommendations, events, jobs, followed);
        return mergedPage(recencyOrdered(candidates), request, candidates.size());
    }

    /**
     * FOR_YOU's deterministic candidate pool: the other rails' own source
     * queries (lost-found, recommendations, events, jobs — the delegated
     * alerts are the official surface above the rails, never a FOR_YOU
     * candidate), with every followed source EXCLUDED (a follow is never
     * replaced by "for you", AC-20-05) and every reason present. Recency
     * over declared scope, no learned model anywhere (decision D-13).
     */
    private List<DiscoveryCardView> forYouCandidates(
            PagedResponse<CommunityDiscoveryPort.DiscoveryPostCard> lostFound,
            PagedResponse<CommunityDiscoveryPort.DiscoveryPostCard> recommendations,
            PagedResponse<CommunityDiscoveryPort.DiscoveryEventCard> events,
            PagedResponse<JobsDiscoveryPort.DiscoveryJobCard> jobs,
            FollowedSources followed) {
        List<DiscoveryCardView> candidates = new ArrayList<>();
        for (CommunityDiscoveryPort.DiscoveryPostCard post : lostFound.content()) {
            if (!followed.authorIds().contains(post.authorId())) {
                candidates.add(postCard(post, REASON_FOR_YOU));
            }
        }
        for (CommunityDiscoveryPort.DiscoveryPostCard post : recommendations.content()) {
            if (!followed.authorIds().contains(post.authorId())) {
                candidates.add(postCard(post, REASON_FOR_YOU));
            }
        }
        for (CommunityDiscoveryPort.DiscoveryEventCard event : events.content()) {
            candidates.add(eventCard(event, REASON_FOR_YOU));
        }
        for (JobsDiscoveryPort.DiscoveryJobCard job : jobs.content()) {
            candidates.add(jobCard(job, REASON_FOR_YOU));
        }
        return dedup(candidates);
    }

    // ------------------------------------------------------------------
    // The projection mappers (the source's own facts — nothing invented)
    // ------------------------------------------------------------------

    /** A community post as the card speaks it — the lifecycle state honest (AC-20-09/AC-20-10). */
    private DiscoveryCardView postCard(CommunityDiscoveryPort.DiscoveryPostCard post, String reason) {
        return new DiscoveryCardView(SOURCE_NEIGHBORHOOD_POST, post.postId(), post.updatedAt(),
                post.title(), post.body(),
                post.lostFoundState() != null ? post.lostFoundState() : post.status(),
                post.locationId(), reason, null);
    }

    /** A neighborhood event as the card speaks it — the status travels (CANCELLED never masquerades). */
    private DiscoveryCardView eventCard(CommunityDiscoveryPort.DiscoveryEventCard event, String reason) {
        return new DiscoveryCardView(SOURCE_NEIGHBORHOOD_EVENT, event.eventId(), event.updatedAt(),
                event.title(), event.description(), event.status(),
                event.locationId(), reason, null);
    }

    /** An open job as the card speaks it — only OPEN jobs enter the port by contract. */
    private DiscoveryCardView jobCard(JobsDiscoveryPort.DiscoveryJobCard job, String reason) {
        return new DiscoveryCardView(SOURCE_JOB, job.jobId(), job.updatedAt(),
                job.title(), job.description(), job.status(), null, reason, null);
    }

    /** A followed provider's active listing as the card speaks it (the summary's own fields). */
    private DiscoveryCardView listingCard(ProviderListingSummary listing, String reason) {
        return new DiscoveryCardView(SOURCE_PROVIDER_LISTING, listing.id(), listing.updatedAt(),
                listing.title(), listing.category(), listing.status(), null, reason, null);
    }

    // ------------------------------------------------------------------
    // The row shaping (dedup AC-20-03, recency, the home bound)
    // ------------------------------------------------------------------

    private void addRowIfNonEmpty(List<DiscoveryRowView> rows, DiscoveryRowType row,
                                  List<DiscoveryCardView> cards, long totalEligible) {
        if (!cards.isEmpty()) {
            rows.add(new DiscoveryRowView(row, cards, totalEligible));
        }
    }

    /** The aggregated home view's per-row bound — the topic page (rowPage) is where the full list lives. */
    private List<DiscoveryCardView> bound(List<DiscoveryCardView> cards) {
        return cards.size() <= HOME_ROW_CARD_LIMIT ? cards : cards.subList(0, HOME_ROW_CARD_LIMIT);
    }

    /**
     * The within-row dedup (AC-20-03 — the rail assembler's duty): the
     * first occurrence of every (sourceType, sourceId) pair survives, in
     * encounter order — the caller's (the port's) own deterministic order
     * is preserved wherever this service did not merge two sources.
     */
    private List<DiscoveryCardView> dedup(List<DiscoveryCardView> cards) {
        Map<String, DiscoveryCardView> unique = new LinkedHashMap<>(cards.size());
        for (DiscoveryCardView card : cards) {
            unique.putIfAbsent(card.sourceType() + ":" + card.sourceId(), card);
        }
        return List.copyOf(unique.values());
    }

    /** Two source slices under one rail, deduped across the pair. */
    private List<DiscoveryCardView> mergeCards(List<DiscoveryCardView> first, List<DiscoveryCardView> second) {
        List<DiscoveryCardView> merged = new ArrayList<>(first.size() + second.size());
        merged.addAll(first);
        merged.addAll(second);
        return dedup(merged);
    }

    /**
     * The merged rails' order: recency (updatedAt DESC) with the sourceId
     * as the complete key's tiebreaker — deterministic pages, no wobbly
     * boundaries (the L32 lesson).
     */
    private List<DiscoveryCardView> recencyOrdered(List<DiscoveryCardView> cards) {
        return cards.stream()
                .sorted(Comparator.comparing(DiscoveryCardView::updatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(DiscoveryCardView::sourceId))
                .toList();
    }

    /** The merged page's envelope: the sources' honest sum, the derived page metadata. */
    private PagedResponse<DiscoveryCardView> mergedPage(List<DiscoveryCardView> cards,
                                                        PagedRequest request, long totalElements) {
        List<DiscoveryCardView> window = cards.size() <= request.size()
                ? cards : cards.subList(0, request.size());
        int totalPages = (int) Math.ceil(totalElements / (double) request.size());
        boolean last = (long) (request.page() + 1) * request.size() >= totalElements;
        return new PagedResponse<>(window, request.page(), request.size(), totalElements, totalPages, last);
    }

    /**
     * The caller's live follows split by type — USER follows feed the
     * community leg, PROVIDER follows the listings leg; GROUP follows are
     * honestly IGNORED (no published group content exists yet — the rail
     * never fabricates what no source provides).
     */
    private FollowedSources followedSources(UUID userId) {
        Set<UUID> authorIds = new HashSet<>();
        Set<UUID> providerIds = new HashSet<>();
        for (FollowedSourcesPort.FollowedSource source : followedSourcesPort.followedSources(userId)) {
            switch (source.type() == null ? "" : source.type()) {
                case FOLLOW_TYPE_USER -> authorIds.add(source.sourceId());
                case FOLLOW_TYPE_PROVIDER -> providerIds.add(source.sourceId());
                // GROUP — no published group posts exist yet; honestly absent, never invented.
                default -> {
                }
            }
        }
        return new FollowedSources(authorIds, providerIds);
    }

    /** The followed sources split into the rail's two legs. */
    private record FollowedSources(Set<UUID> authorIds, Set<UUID> providerIds) {
    }
}
