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
