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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Wave D-1 (plan #536 §1.4 / JT-20): the discovery engine's contracts —
 * the honest empty scope (no membership, no widening), the empty rail's
 * total omission (AC-20-07), the within-row dedup by the source pair
 * (AC-20-03), the home view's 10-card bound, FOR_YOU's deterministic
 * follow-exclusion with the reason always present (AC-20-05, D-13), the
 * topic page's straight port passthrough, and the impression ledger's
 * skip-and-absorb bridge discipline (the V93 form).
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID NEIGHBORHOOD_ID = UUID.randomUUID();

    @Mock
    private CommunityMembershipPort membershipPort;

    @Mock
    private CommunityDiscoveryPort communityPort;

    @Mock
    private JobsDiscoveryPort jobsPort;

    @Mock
    private UrgentAlertsPort urgentAlertsPort;

    @Mock
    private FollowedSourcesPort followedSourcesPort;

    @Mock
    private ProviderListingsPort providerListingsPort;

    @Mock
    private DiscoveryImpressionRepository impressions;

    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);

    private DiscoveryService service() {
        return new DiscoveryService(membershipPort, communityPort, jobsPort, urgentAlertsPort,
                followedSourcesPort, providerListingsPort, impressions, clock);
    }

    private void callerHasMembership() {
        when(membershipPort.getActiveNeighborhoodId(USER_ID)).thenReturn(Optional.of(NEIGHBORHOOD_ID));
    }

    private void callerHasNoMembership() {
        when(membershipPort.getActiveNeighborhoodId(USER_ID)).thenReturn(Optional.empty());
    }

    private void callerFollowsNobody() {
        when(followedSourcesPort.followedSources(USER_ID)).thenReturn(Set.of());
    }

    /** The ports' honest empty pages — every eligibility query quiet. */
    private void everyRailQuiet() {
        when(urgentAlertsPort.findActive(NEIGHBORHOOD_ID, now)).thenReturn(List.of());
        when(communityPort.findActiveLostFound(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(emptyPage());
        when(communityPort.findRecommendations(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(emptyPage());
        when(communityPort.findUpcomingEvents(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(emptyPage());
        when(jobsPort.findOpenJobs(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(emptyPage());
    }

    private static <T> PagedResponse<T> emptyPage() {
        return new PagedResponse<>(List.of(), 0, 10, 0, 0, true);
    }

    private static <T> PagedResponse<T> page(List<T> content, long total) {
        return new PagedResponse<>(content, 0, 10, total,
                (int) Math.ceil(total / 10.0), 10 >= total);
    }

    private static CommunityDiscoveryPort.DiscoveryPostCard post(UUID postId, UUID authorId, Instant updatedAt) {
        return new CommunityDiscoveryPort.DiscoveryPostCard(postId, authorId, "LOST_FOUND",
                "ACTIVE", "فقرة القطعة المفقودة", "المحتوى", "VISIBLE", NEIGHBORHOOD_ID, updatedAt);
    }

    CommunityDiscoveryPort.DiscoveryEventCard event(UUID eventId, Instant updatedAt) {
        return new CommunityDiscoveryPort.DiscoveryEventCard(eventId, NEIGHBORHOOD_ID,
                "فعالية الحي", "الوصف", "ACTIVE", "الساحة", now, now, updatedAt);
    }

    private static JobsDiscoveryPort.DiscoveryJobCard job(UUID jobId, Instant updatedAt) {
        return new JobsDiscoveryPort.DiscoveryJobCard(jobId, "وظيفة", "الوصف",
                "FULL_TIME", "HYBRID", "OPEN", updatedAt);
    }

    private static ProviderListingSummary listing(UUID listingId, UUID providerId, Instant updatedAt) {
        return new ProviderListingSummary(listingId, "إعلان المتابَع", "SERVICES",
                java.math.BigDecimal.TEN, "SAR", providerId, "ACTIVE", updatedAt, updatedAt);
    }

    UrgentAlertsPort.UrgentAlertCard alert(UUID alertId, Instant updatedAt) {
        return new UrgentAlertsPort.UrgentAlertCard(alertId, UUID.randomUUID(), "البلدية",
                "GOVERNMENT", "HIGH", "تنبيه رسمي", "المحتوى", NEIGHBORHOOD_ID, now, now, updatedAt);
    }

    @Test
    void homeWithoutMembershipIsTheHonestEmptyListWithNoWidening() {
        callerHasNoMembership();

        assertThat(service().homeRows(USER_ID)).isEmpty();

        // No membership, no scope — not one eligibility query runs on a
        // widened surface (AC-02-02: the expansion is an explicit user act).
        verifyNoInteractions(communityPort, jobsPort, urgentAlertsPort,
                followedSourcesPort, providerListingsPort, impressions);
    }

    @Test
    void emptyRailsAreOmittedFromTheHomeResponseEntirely() {
        callerHasMembership();
        callerFollowsNobody();
        everyRailQuiet();

        List<DiscoveryRowView> rows = service().homeRows(USER_ID);

        // AC-20-07: a quiet rail is ABSENT — never a row with empty cards.
        assertThat(rows).isEmpty();
    }

    @Test
    void homeKeepsTheRailOrderAndTheHonestReasonOnEveryCard() {
        callerHasMembership();
        callerFollowsNobody();
        UUID alertId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(urgentAlertsPort.findActive(NEIGHBORHOOD_ID, now)).thenReturn(List.of(alert(alertId, now)));
        when(communityPort.findActiveLostFound(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(page(List.of(post(postId, UUID.randomUUID(), now)), 1));
        when(communityPort.findRecommendations(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(emptyPage());
        when(communityPort.findUpcomingEvents(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(emptyPage());
        when(jobsPort.findOpenJobs(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(page(List.of(job(jobId, now)), 1));

        List<DiscoveryRowView> rows = service().homeRows(USER_ID);

        // §1.4's rail order: the official surface above the ordinary rails;
        // FOR_YOU's candidate pool (the quiet rails' queries still ran).
        assertThat(rows).extracting(DiscoveryRowView::row)
                .containsExactly(DiscoveryRowType.URGENT_ALERTS, DiscoveryRowType.LOST_FOUND,
                        DiscoveryRowType.EVENTS_AND_OPPORTUNITIES, DiscoveryRowType.FOR_YOU);
        assertThat(rows.get(0).cards().get(0).sourceType()).isEqualTo("URGENT_ALERT");
        assertThat(rows.get(0).cards().get(0).sourceId()).isEqualTo(alertId);
        assertThat(rows.get(0).cards().get(0).reason()).isEqualTo(DiscoveryService.REASON_URGENT_ALERTS);
        assertThat(rows.get(1).cards().get(0).reason()).isEqualTo(DiscoveryService.REASON_LOST_FOUND);
        assertThat(rows.get(2).cards().get(0).reason()).isEqualTo(DiscoveryService.REASON_JOB);
        assertThat(rows.get(0).totalEligible()).isEqualTo(1);
    }

    @Test
    void homeRowIsDedupedByTheSourcePairAndBoundedAtTenCards() {
        callerHasMembership();
        callerFollowsNobody();
        UUID duplicatedEventId = UUID.randomUUID();
        List<CommunityDiscoveryPort.DiscoveryEventCard> events = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            events.add(event(i == 5 ? duplicatedEventId : UUID.randomUUID(), now.minusSeconds(i)));
        }
        // The source stumbled: the same event twice (AC-20-03's own duty).
        events.add(event(duplicatedEventId, now));
        when(urgentAlertsPort.findActive(NEIGHBORHOOD_ID, now)).thenReturn(List.of());
        when(communityPort.findActiveLostFound(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(emptyPage());
        when(communityPort.findRecommendations(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(emptyPage());
        when(communityPort.findUpcomingEvents(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(page(events, 7));
        when(jobsPort.findOpenJobs(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(page(List.of(job(UUID.randomUUID(), now), job(UUID.randomUUID(), now),
                        job(UUID.randomUUID(), now), job(UUID.randomUUID(), now),
                        job(UUID.randomUUID(), now), job(UUID.randomUUID(), now)), 6));

        DiscoveryRowView row = service().homeRows(USER_ID).stream()
                .filter(r -> r.row() == DiscoveryRowType.EVENTS_AND_OPPORTUNITIES)
                .findFirst().orElseThrow();

        // 7 events (one duplicated) + 6 jobs = 12 eligible → 10 cards after
        // the dedup and the home bound; the source-side total stays honest.
        assertThat(row.cards()).hasSize(DiscoveryService.HOME_ROW_CARD_LIMIT);
        assertThat(row.cards()).extracting(DiscoveryCardView::sourceId).doesNotHaveDuplicates();
        assertThat(row.totalEligible()).isEqualTo(13);
    }

    @Test
    void forYouExcludesFollowedAuthorsAndAlwaysCarriesItsReason() {
        callerHasMembership();
        UUID followedAuthor = UUID.randomUUID();
        when(followedSourcesPort.followedSources(USER_ID))
                .thenReturn(Set.of(new FollowedSourcesPort.FollowedSource("USER", followedAuthor)));
        UUID followedPostId = UUID.randomUUID();
        UUID strangerPostId = UUID.randomUUID();
        when(urgentAlertsPort.findActive(NEIGHBORHOOD_ID, now)).thenReturn(List.of());
        when(communityPort.findActiveLostFound(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(page(List.of(post(followedPostId, followedAuthor, now),
                        post(strangerPostId, UUID.randomUUID(), now)), 2));
        when(communityPort.findRecommendations(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(emptyPage());
        when(communityPort.findUpcomingEvents(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(emptyPage());
        when(jobsPort.findOpenJobs(NEIGHBORHOOD_ID, PagedRequest.of(0, 10)))
                .thenReturn(emptyPage());
        when(communityPort.findRecentByAuthors(Set.of(followedAuthor), PagedRequest.of(0, 10)))
                .thenReturn(page(List.of(post(followedPostId, followedAuthor, now)), 1));
        // No PROVIDER follows — the listings leg is never queried.

        List<DiscoveryRowView> rows = service().homeRows(USER_ID);

        DiscoveryRowView followed = rows.stream()
                .filter(r -> r.row() == DiscoveryRowType.FOLLOWED_SOURCES).findFirst().orElseThrow();
        assertThat(followed.cards()).extracting(DiscoveryCardView::sourceId)
                .containsExactly(followedPostId);
        assertThat(followed.cards().get(0).reason()).isEqualTo(DiscoveryService.REASON_FOLLOWED_SOURCES);

        // AC-20-05: the explicit follow is never replaced by "for you" —
        // the followed author's post is OUT of FOR_YOU, the stranger's in.
        DiscoveryRowView forYou = rows.stream()
                .filter(r -> r.row() == DiscoveryRowType.FOR_YOU).findFirst().orElseThrow();
        assertThat(forYou.cards()).extracting(DiscoveryCardView::sourceId)
                .containsExactly(strangerPostId);
        assertThat(forYou.cards()).allSatisfy(card ->
                assertThat(card.reason()).isEqualTo(DiscoveryService.REASON_FOR_YOU));
    }

    @Test
    void groupFollowsAreHonestlyIgnoredNeverFabricated() {
        callerHasMembership();
        when(followedSourcesPort.followedSources(USER_ID)).thenReturn(
                Set.of(new FollowedSourcesPort.FollowedSource("GROUP", UUID.randomUUID())));
        everyRailQuiet();

        List<DiscoveryRowView> rows = service().homeRows(USER_ID);

        // No published group content exists yet — the rail is absent, the
        // community leg was never queried with an invented set, and the
        // listings leg never ran.
        assertThat(rows).isEmpty();
        verify(communityPort, never()).findRecentByAuthors(any(), any());
        verifyNoInteractions(providerListingsPort);
    }

    @Test
    void rowPageWithoutMembershipIsTheHonestEmptyPage() {
        callerHasNoMembership();

        PagedResponse<DiscoveryCardView> result =
                service().rowPage(USER_ID, DiscoveryRowType.LOST_FOUND, PagedRequest.of(0, 20));

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.totalElements()).isZero();
        verifyNoInteractions(communityPort, jobsPort, urgentAlertsPort,
                followedSourcesPort, providerListingsPort);
    }

    @Test
    void lostFoundRowPagePassesThePortPageStraightThrough() {
        callerHasMembership();
        PagedRequest request = PagedRequest.of(2, 20);
        UUID postId = UUID.randomUUID();
        when(communityPort.findActiveLostFound(NEIGHBORHOOD_ID, request))
                .thenReturn(new PagedResponse<>(List.of(post(postId, UUID.randomUUID(), now)),
                        2, 20, 45, 3, false));

        PagedResponse<DiscoveryCardView> result =
                service().rowPage(USER_ID, DiscoveryRowType.LOST_FOUND, request);

        // "عرض الكل" (UJ-261/262): the port's FULL page, the metadata intact.
        assertThat(result.pageNumber()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(45);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.last()).isFalse();
        assertThat(result.content().get(0).sourceId()).isEqualTo(postId);
        assertThat(result.content().get(0).sourceType()).isEqualTo("NEIGHBORHOOD_POST");
        assertThat(result.content().get(0).state()).isEqualTo("ACTIVE");
        assertThat(result.content().get(0).reason()).isEqualTo(DiscoveryService.REASON_LOST_FOUND);
    }

    @Test
    void forYouTopicPageIsLastOnlyWhenEverySourcePageIsLast() {
        // CodeRabbit round on the #540 slice: the FOR_YOU topic page's
        // `last` is derived from the SOURCE pages, never from the current
        // page's candidate count — page 0 with more candidates than `size`
        // answered last=false only by accident before, and a later sparse
        // page answered last=true while the sources still had rows.
        callerHasMembership();
        PagedRequest request = PagedRequest.of(0, 10);
        when(followedSourcesPort.followedSources(USER_ID)).thenReturn(Set.of());
        // lost-found still has a second page; recommendations/events/jobs
        // are done — the merged page is NOT last.
        when(communityPort.findActiveLostFound(NEIGHBORHOOD_ID, request))
                .thenReturn(new PagedResponse<>(
                        List.of(post(UUID.randomUUID(), UUID.randomUUID(), now)),
                        0, 10, 15, 2, false));
        when(communityPort.findRecommendations(NEIGHBORHOOD_ID, request))
                .thenReturn(new PagedResponse<>(List.of(), 0, 10, 0, 0, true));
        when(communityPort.findUpcomingEvents(NEIGHBORHOOD_ID, request))
                .thenReturn(new PagedResponse<>(List.of(), 0, 10, 0, 0, true));
        when(jobsPort.findOpenJobs(NEIGHBORHOOD_ID, request))
                .thenReturn(new PagedResponse<>(List.of(), 0, 10, 0, 0, true));

        PagedResponse<DiscoveryCardView> notLast =
                service().rowPage(USER_ID, DiscoveryRowType.FOR_YOU, request);
        assertThat(notLast.last()).isFalse();
        assertThat(notLast.totalElements()).isEqualTo(15L);

        // Every source page last => the merged page is last.
        when(communityPort.findActiveLostFound(NEIGHBORHOOD_ID, request))
                .thenReturn(new PagedResponse<>(
                        List.of(post(UUID.randomUUID(), UUID.randomUUID(), now)),
                        0, 10, 1, 1, true));
        PagedResponse<DiscoveryCardView> last =
                service().rowPage(USER_ID, DiscoveryRowType.FOR_YOU, request);
        assertThat(last.last()).isTrue();
        assertThat(last.totalElements()).isEqualTo(1L);
    }

    @Test
    void forYouTopicPageCutsItsWindowByRecencyNotSourceOrder() {
        // CodeRabbit round 2 on the #540 slice: the FOR_YOU pool rides the
        // merged rails' own discipline BEFORE the window cuts — a newer
        // recommendations card must out-rank an older lost-found card for
        // the visible window, exactly like EVENTS_AND_OPPORTUNITIES (the
        // pre-fix source-major order let the first source's older card take
        // the cut by iteration order, not by the documented recency D-13).
        callerHasMembership();
        PagedRequest request = PagedRequest.of(0, 1);
        when(followedSourcesPort.followedSources(USER_ID)).thenReturn(Set.of());
        UUID olderLostFoundPost = UUID.randomUUID();
        UUID newerRecommendation = UUID.randomUUID();
        when(communityPort.findActiveLostFound(NEIGHBORHOOD_ID, request))
                .thenReturn(new PagedResponse<>(
                        List.of(post(olderLostFoundPost, UUID.randomUUID(), now.minusSeconds(30))),
                        0, 1, 1, 1, true));
        when(communityPort.findRecommendations(NEIGHBORHOOD_ID, request))
                .thenReturn(new PagedResponse<>(
                        List.of(post(newerRecommendation, UUID.randomUUID(), now)),
                        0, 1, 1, 1, true));
        when(communityPort.findUpcomingEvents(NEIGHBORHOOD_ID, request))
                .thenReturn(new PagedResponse<>(List.of(), 0, 1, 0, 0, true));
        when(jobsPort.findOpenJobs(NEIGHBORHOOD_ID, request))
                .thenReturn(new PagedResponse<>(List.of(), 0, 1, 0, 0, true));

        PagedResponse<DiscoveryCardView> result =
                service().rowPage(USER_ID, DiscoveryRowType.FOR_YOU, request);

        // The window's single cut is the NEWEST candidate (recency), never
        // the first source's card by iteration order; the envelope stays
        // the sources' honest sum.
        assertThat(result.content()).extracting(DiscoveryCardView::sourceId)
                .containsExactly(newerRecommendation);
        assertThat(result.content()).extracting(DiscoveryCardView::sourceType)
                .containsExactly("NEIGHBORHOOD_POST");
        assertThat(result.totalElements()).isEqualTo(2L);
    }

    @Test
    void forYouTopicPageOmittedCardsStayEligibleForLaterPages() {
        // CodeRabbit round 3 on the #540 slice: the FOR_YOU window is a
        // GLOBAL slice over the recency-ordered pool — every source
        // contributes its FIRST (page+1)*size rows (the k-way merge over
        // sorted sources), so a card cut from page 0's window stays ranked
        // in the global pool and appears on its own later page. The
        // pre-fix per-source cursors (each page fetching each source's OWN
        // page N) let the cut card fall between the cursors — lost forever.
        callerHasMembership();
        // Page 1 of size 2: the deep fetch each source must answer is
        // (0, (1+1)*2) = the first 4 rows — asserted on the wire below.
        PagedRequest request = PagedRequest.of(1, 2);
        PagedRequest deep = PagedRequest.of(0, 4);
        when(followedSourcesPort.followedSources(USER_ID)).thenReturn(Set.of());
        UUID l1 = UUID.randomUUID();
        UUID l2 = UUID.randomUUID();
        UUID l3 = UUID.randomUUID();
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        // Lost-found: 5 rows total, its deep page carries the first 4 (not
        // last). Recency: L1(now) > L2(now-10) > L3(now-20).
        when(communityPort.findActiveLostFound(NEIGHBORHOOD_ID, deep))
                .thenReturn(new PagedResponse<>(List.of(
                        post(l1, UUID.randomUUID(), now),
                        post(l2, UUID.randomUUID(), now.minusSeconds(10)),
                        post(l3, UUID.randomUUID(), now.minusSeconds(20))),
                        0, 4, 5, 2, false));
        // Recommendations: 2 rows total, its deep page carries them all
        // (last). Recency: R1(now-5) > R2(now-15).
        when(communityPort.findRecommendations(NEIGHBORHOOD_ID, deep))
                .thenReturn(new PagedResponse<>(List.of(
                        post(r1, UUID.randomUUID(), now.minusSeconds(5)),
                        post(r2, UUID.randomUUID(), now.minusSeconds(15))),
                        0, 4, 2, 1, true));
        when(communityPort.findUpcomingEvents(NEIGHBORHOOD_ID, deep))
                .thenReturn(new PagedResponse<>(List.of(), 0, 4, 0, 0, true));
        when(jobsPort.findOpenJobs(NEIGHBORHOOD_ID, deep))
                .thenReturn(new PagedResponse<>(List.of(), 0, 4, 0, 0, true));

        PagedResponse<DiscoveryCardView> result =
                service().rowPage(USER_ID, DiscoveryRowType.FOR_YOU, request);

        // The global pool by recency is L1, R1, L2, R2, L3 — page 1's
        // window is [L2, R2]: the two cards the pre-fix cursors would have
        // skipped past (page 0 cut them; page 1's per-source fetch moved
        // every cursor beyond them). They appear, in the pool's own order.
        assertThat(result.content()).extracting(DiscoveryCardView::sourceId)
                .containsExactly(l2, r2);
        // The deep fetch the merge discipline requires — asserted on the
        // wire: every source consulted with (0, (page+1)*size).
        verify(communityPort).findActiveLostFound(NEIGHBORHOOD_ID, deep);
        verify(communityPort).findRecommendations(NEIGHBORHOOD_ID, deep);
        verify(communityPort).findUpcomingEvents(NEIGHBORHOOD_ID, deep);
        verify(jobsPort).findOpenJobs(NEIGHBORHOOD_ID, deep);
        // The envelope: the sources' honest sum; not last — lost-found's
        // own deep page still has a row beyond the window's reach.
        assertThat(result.totalElements()).isEqualTo(7L);
        assertThat(result.last()).isFalse();
        assertThat(result.pageNumber()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(2);
    }

    @Test
    void followedSourcesTopicPageOmittedCardsStayEligibleForLaterPages() {
        // CodeRabbit's final round on the #544 slice: FOLLOWED_SOURCES is a
        // merged rail — the same global-window law FOR_YOU's round-3 fix
        // rides. Page 1 of size 2: BOTH sources must answer their deep
        // (0, (1+1)*2) fetch; the cards page 0's window cut stay ranked in
        // the global recency pool and appear on their own later page — the
        // pre-fix per-source cursors let them fall between and lose them.
        callerHasMembership();
        PagedRequest request = PagedRequest.of(1, 2);
        PagedRequest deep = PagedRequest.of(0, 4);
        UUID followedAuthor = UUID.randomUUID();
        UUID followedProvider = UUID.randomUUID();
        when(followedSourcesPort.followedSources(USER_ID)).thenReturn(Set.of(
                new FollowedSourcesPort.FollowedSource("USER", followedAuthor),
                new FollowedSourcesPort.FollowedSource("PROVIDER", followedProvider)));
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        UUID l1 = UUID.randomUUID();
        UUID l2 = UUID.randomUUID();
        // The authors leg: 5 rows total, its deep page carries the first 3
        // (not last). Recency: P1(now) > P2(now-10) > P3(now-20).
        when(communityPort.findRecentByAuthors(Set.of(followedAuthor), deep))
                .thenReturn(new PagedResponse<>(List.of(
                        post(p1, followedAuthor, now),
                        post(p2, followedAuthor, now.minusSeconds(10)),
                        post(p3, followedAuthor, now.minusSeconds(20))),
                        0, 4, 5, 2, false));
        // The providers leg: 2 rows total, its deep page carries them all
        // (last). Recency: L1(now-5) > L2(now-15).
        when(providerListingsPort.findActiveByProviders(Set.of(followedProvider), deep))
                .thenReturn(new PagedResponse<>(List.of(
                        listing(l1, followedProvider, now.minusSeconds(5)),
                        listing(l2, followedProvider, now.minusSeconds(15))),
                        0, 4, 2, 1, true));

        PagedResponse<DiscoveryCardView> result =
                service().rowPage(USER_ID, DiscoveryRowType.FOLLOWED_SOURCES, request);

        // The global pool by recency is P1, L1, P2, L2, P3 — page 1's
        // window is [P2, L2]: exactly the two cards the pre-fix cursors
        // would have skipped past.
        assertThat(result.content()).extracting(DiscoveryCardView::sourceId)
                .containsExactly(p2, l2);
        assertThat(result.content()).extracting(DiscoveryCardView::sourceType)
                .containsExactly("NEIGHBORHOOD_POST", "PROVIDER_LISTING");
        verify(communityPort).findRecentByAuthors(Set.of(followedAuthor), deep);
        verify(providerListingsPort).findActiveByProviders(Set.of(followedProvider), deep);
        assertThat(result.totalElements()).isEqualTo(7L);
        assertThat(result.last()).isFalse();
    }

    @Test
    void eventsAndOpportunitiesRowPageMergesItsSourcesByRecency() {
        callerHasMembership();
        PagedRequest request = PagedRequest.of(0, 50);
        UUID olderEvent = UUID.randomUUID();
        UUID newestEvent = UUID.randomUUID();
        UUID oldestJob = UUID.randomUUID();
        when(communityPort.findUpcomingEvents(NEIGHBORHOOD_ID, request))
                .thenReturn(new PagedResponse<>(List.of(event(olderEvent, now.minusSeconds(10)),
                        event(newestEvent, now.plusSeconds(10))), 0, 50, 2, 1, true));
        when(jobsPort.findOpenJobs(NEIGHBORHOOD_ID, request))
                .thenReturn(new PagedResponse<>(List.of(job(oldestJob, now.minusSeconds(60))),
                        0, 50, 1, 1, true));

        PagedResponse<DiscoveryCardView> result =
                service().rowPage(USER_ID, DiscoveryRowType.EVENTS_AND_OPPORTUNITIES, request);

        // The merged rail's deterministic order: updatedAt DESC (L32).
        assertThat(result.content()).extracting(DiscoveryCardView::sourceId)
                .containsExactly(newestEvent, olderEvent, oldestJob);
        assertThat(result.content()).extracting(DiscoveryCardView::sourceType)
                .containsExactly("NEIGHBORHOOD_EVENT", "NEIGHBORHOOD_EVENT", "JOB");
        assertThat(result.totalElements()).isEqualTo(3);
    }

    @Test
    void urgentAlertsRowPageRendersTheWholeOfficialListAsOnePage() {
        callerHasMembership();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        // The adapter's own order — never re-ranked (AC-20-06).
        when(urgentAlertsPort.findActive(NEIGHBORHOOD_ID, now))
                .thenReturn(List.of(alert(first, now.minusSeconds(5)), alert(second, now)));
        PagedRequest request = PagedRequest.of(0, 20);

        PagedResponse<DiscoveryCardView> result =
                service().rowPage(USER_ID, DiscoveryRowType.URGENT_ALERTS, request);

        assertThat(result.content()).extracting(DiscoveryCardView::sourceId)
                .containsExactly(first, second);
        assertThat(result.pageNumber()).isZero();
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.last()).isTrue();
    }

    @Test
    void recordImpressionWritesTodayThroughTheLedgerBridge() {
        UUID sourceId = UUID.randomUUID();

        service().recordImpression(USER_ID, DiscoveryRowType.FOR_YOU, "NEIGHBORHOOD_POST", sourceId);

        // The display day is today on the platform clock (UTC in production).
        verify(impressions).insertOnce(any(), eq(USER_ID), eq("FOR_YOU"),
                eq("NEIGHBORHOOD_POST"), eq(sourceId), eq(LocalDate.of(2026, 10, 7)));
    }

    @Test
    void recordImpressionAbsorbsALedgerFailureTheBrowseNeverBreaks() {
        UUID sourceId = UUID.randomUUID();
        doThrow(new IllegalStateException("database unavailable"))
                .when(impressions).insertOnce(any(), any(), any(), any(), any(), any());

        // The 204-always contract: bookkeeping failure ≠ browsing failure.
        assertThatCode(() -> service().recordImpression(
                        USER_ID, DiscoveryRowType.FOR_YOU, "NEIGHBORHOOD_POST", sourceId))
                .doesNotThrowAnyException();
    }
}
