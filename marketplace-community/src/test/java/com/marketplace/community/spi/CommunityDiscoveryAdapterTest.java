package com.marketplace.community.spi;

import com.marketplace.community.LostFoundState;
import com.marketplace.community.NeighborhoodEvent;
import com.marketplace.community.NeighborhoodEventRepository;
import com.marketplace.community.NeighborhoodEventStatus;
import com.marketplace.community.NeighborhoodPost;
import com.marketplace.community.NeighborhoodPostRepository;
import com.marketplace.community.PostCategory;
import com.marketplace.community.PostStatus;
import com.marketplace.shared.api.CommunityDiscoveryPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SpringPagination;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JT-20 — the discovery adapter's eligibility-first contract, unit-pinned
 * (the {@code MediaLookupAdapterTest} delegation shape over the module's
 * own Specifications-test predicate reads):
 *
 * <ul>
 *   <li>every post rail composes the moderation floor (VISIBLE) — a
 *       hidden post never surfaces, by predicate, not by luck; the
 *       author-deleted are absent through the entity's {@code @SoftDelete}
 *       filter (Hibernate's own mechanism, no predicate of its own);</li>
 *   <li>the lost-and-found rail's state gate is ACTIVE only — a resolved
 *       or recovered report never masquerades as an active one;</li>
 *   <li>the events rail is status-honest — the upcoming floor carries the
 *       literal CANCELLED/POSTPONED exclusion;</li>
 *   <li>the followed-sources leg: the honest empty page, the loud cap,
 *       the IN on authors;</li>
 *   <li>each rail pins its own deterministic complete sort key (D-N5).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CommunityDiscoveryAdapterTest {

    private static final Instant FIXED = Instant.parse("2026-09-17T09:30:00Z");

    @Mock
    private NeighborhoodPostRepository postRepository;

    @Mock
    private NeighborhoodEventRepository eventRepository;

    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

    private CommunityDiscoveryAdapter adapter() {
        return new CommunityDiscoveryAdapter(postRepository, eventRepository, clock);
    }

    private UUID locationId = UUID.randomUUID();

    private NeighborhoodPost post(PostCategory category) {
        return NeighborhoodPost.post(UUID.randomUUID(), locationId,
                category, "Title", "Body", clock);
    }

    @SuppressWarnings("unchecked")
    private Page<NeighborhoodPost> onePostPage(NeighborhoodPost post) {
        return new PageImpl<>(List.of(post));
    }

    // ---------- findActiveLostFound ----------

    @Test
    @SuppressWarnings("unchecked")
    void findActiveLostFound_composesEveryEligibilityGate() {
        when(postRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        Root<NeighborhoodPost> root = mock(Root.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> locationPath = mock(Path.class);
        Path<Object> statusPath = mock(Path.class);
        Path<Object> categoryPath = mock(Path.class);
        Path<Object> statePath = mock(Path.class);
        when(root.get("locationId")).thenReturn(locationPath);
        when(root.get("status")).thenReturn(statusPath);
        when(root.get("category")).thenReturn(categoryPath);
        when(root.get("lostFoundState")).thenReturn(statePath);

        adapter().findActiveLostFound(locationId, PagedRequest.of(0, 20));

        ArgumentCaptor<Specification<NeighborhoodPost>> spec =
                ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(postRepository).findAll(spec.capture(), pageable.capture());

        spec.getValue().toPredicate(root, null, cb);

        // The eligibility set, deterministic and complete: the
        // neighborhood scope, the moderation floor (a HIDDEN post never
        // surfaces — the V173 partial index's own WHERE), the category,
        // and the lifecycle state (a resolved report never masquerades
        // as active).
        verify(cb).equal(locationPath, locationId);
        verify(cb).equal(statusPath, PostStatus.VISIBLE);
        verify(cb).equal(categoryPath, PostCategory.LOST_FOUND);
        verify(cb).equal(statePath, LostFoundState.ACTIVE);

        // The rail pins its own deterministic key: the V173 index's
        // shape (updatedAt DESC) with the id tiebreak (D-N5).
        assertThat(pageable.getValue().getSort().getOrderFor("updatedAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getValue().getSort().getOrderFor("id").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void findActiveLostFound_mapsTheCards_withTheStateOnlyWhereItExists() {
        NeighborhoodPost lost = post(PostCategory.LOST_FOUND);
        NeighborhoodPost general = post(PostCategory.GENERAL);
        when(postRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(onePostPage(lost));

        PagedResponse<CommunityDiscoveryPort.DiscoveryPostCard> response =
                adapter().findActiveLostFound(locationId, PagedRequest.of(0, 20));

        CommunityDiscoveryPort.DiscoveryPostCard card = response.content().get(0);
        assertThat(card.postId()).isEqualTo(lost.getId());
        assertThat(card.authorId()).isEqualTo(lost.getAuthorId());
        assertThat(card.category()).isEqualTo("LOST_FOUND");
        assertThat(card.lostFoundState()).isEqualTo("ACTIVE");
        assertThat(card.title()).isEqualTo("Title");
        assertThat(card.body()).isEqualTo("Body");
        assertThat(card.status()).isEqualTo("VISIBLE");
        assertThat(card.locationId()).isEqualTo(locationId);
        assertThat(card.updatedAt()).isEqualTo(lost.getUpdatedAt());
        assertThat(response.totalElements()).isEqualTo(1L);

        // The projection rule holds across every rail: a non-LOST_FOUND
        // post carries NO state.
        when(postRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(onePostPage(general));
        assertThat(adapter().findRecommendations(locationId, PagedRequest.of(0, 20))
                .content().get(0).lostFoundState()).isNull();
    }

    // ---------- findRecommendations ----------

    @Test
    @SuppressWarnings("unchecked")
    void findRecommendations_scopesAndOrdersNewestFirst() {
        when(postRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        Root<NeighborhoodPost> root = mock(Root.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> locationPath = mock(Path.class);
        Path<Object> statusPath = mock(Path.class);
        Path<Object> categoryPath = mock(Path.class);
        when(root.get("locationId")).thenReturn(locationPath);
        when(root.get("status")).thenReturn(statusPath);
        when(root.get("category")).thenReturn(categoryPath);

        adapter().findRecommendations(locationId, PagedRequest.of(1, 10));

        ArgumentCaptor<Specification<NeighborhoodPost>> spec =
                ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(postRepository).findAll(spec.capture(), pageable.capture());

        spec.getValue().toPredicate(root, null, cb);
        verify(cb).equal(locationPath, locationId);
        verify(cb).equal(statusPath, PostStatus.VISIBLE);
        verify(cb).equal(categoryPath, PostCategory.RECOMMENDATION);

        // Newest first, id tiebreak — the port's own contract words.
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getValue().getSort().getOrderFor("id").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    // ---------- findUpcomingEvents ----------

    @Test
    @SuppressWarnings("unchecked")
    void findUpcomingEvents_isStatusHonest_andScoped() {
        when(eventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<NeighborhoodEvent>(List.of()));
        Root<NeighborhoodEvent> root = mock(Root.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> locationPath = mock(Path.class);
        Path<Instant> startsPath = mock(Path.class);
        Path<Object> statusPath = mock(Path.class);
        jakarta.persistence.criteria.Predicate withdrawn =
                mock(jakarta.persistence.criteria.Predicate.class);
        when(root.get("locationId")).thenReturn(locationPath);
        when(root.get("startsAt")).thenReturn((Path) startsPath);
        when(root.get("status")).thenReturn(statusPath);
        when(statusPath.in(NeighborhoodEventStatus.CANCELLED,
                NeighborhoodEventStatus.POSTPONED)).thenReturn(withdrawn);

        adapter().findUpcomingEvents(locationId, PagedRequest.of(0, 20));

        ArgumentCaptor<Specification<NeighborhoodEvent>> spec =
                ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(eventRepository).findAll(spec.capture(), pageable.capture());

        spec.getValue().toPredicate(root, null, cb);

        // The neighborhood scope + the status-honest upcoming floor: a
        // CANCELLED or POSTPONED event never masquerades as upcoming.
        verify(cb).equal(locationPath, locationId);
        verify(cb).greaterThanOrEqualTo(startsPath, FIXED);
        verify(statusPath).in(NeighborhoodEventStatus.CANCELLED,
                NeighborhoodEventStatus.POSTPONED);
        verify(cb).not(withdrawn);

        // The board's own complete sort key, soonest first (D-N5).
        assertThat(pageable.getValue().getSort().getOrderFor("startsAt").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        assertThat(pageable.getValue().getSort().getOrderFor("id").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void findUpcomingEvents_mapsTheCards_withTheHonestStatus() {
        NeighborhoodEvent event = NeighborhoodEvent.event(UUID.randomUUID(), locationId,
                com.marketplace.community.EventCategory.VOLUNTEER, "Park cleanup morning",
                "Tools provided.", FIXED.plusSeconds(86_400), null,
                "Community garden — main gate", "Development committee",
                null, com.marketplace.community.EventRegistration.OPEN);
        when(eventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event)));

        PagedResponse<CommunityDiscoveryPort.DiscoveryEventCard> response =
                adapter().findUpcomingEvents(locationId, PagedRequest.of(0, 20));

        CommunityDiscoveryPort.DiscoveryEventCard card = response.content().get(0);
        assertThat(card.eventId()).isEqualTo(event.getId());
        assertThat(card.locationId()).isEqualTo(locationId);
        assertThat(card.title()).isEqualTo("Park cleanup morning");
        assertThat(card.description()).isEqualTo("Tools provided.");
        assertThat(card.status()).isEqualTo("ACTIVE");
        assertThat(card.locationLabel()).isEqualTo("Community garden — main gate");
        assertThat(card.startsAt()).isEqualTo(event.getStartsAt());
        assertThat(card.updatedAt()).isEqualTo(event.getUpdatedAt());
    }

    // ---------- findRecentByAuthors ----------

    @Test
    void findRecentByAuthors_emptySet_isTheHonestEmptyPage_neverAQuery() {
        PagedResponse<CommunityDiscoveryPort.DiscoveryPostCard> response =
                adapter().findRecentByAuthors(Set.of(), PagedRequest.of(0, 20));

        assertThat(response.isEmpty()).isTrue();
        assertThat(response.pageNumber()).isEqualTo(0);
        assertThat(response.last()).isTrue();
        verifyNoInteractions(postRepository);
    }

    @Test
    void findRecentByAuthors_overTheCap_isLoud_neverSilentlyTruncated() {
        Set<UUID> tooMany = IntStream.range(0, 101)
                .mapToObj(i -> UUID.randomUUID())
                .collect(java.util.stream.Collectors.toSet());

        assertThatThrownBy(() -> adapter().findRecentByAuthors(tooMany, PagedRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
        verifyNoInteractions(postRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findRecentByAuthors_carriesTheInClause_andTheModerationFloor() {
        Set<UUID> authors = Set.of(UUID.randomUUID(), UUID.randomUUID());
        when(postRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        Root<NeighborhoodPost> root = mock(Root.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> statusPath = mock(Path.class);
        Path<Object> authorPath = mock(Path.class);
        when(root.get("status")).thenReturn(statusPath);
        when(root.get("authorId")).thenReturn(authorPath);

        adapter().findRecentByAuthors(authors, PagedRequest.of(0, 20));

        ArgumentCaptor<Specification<NeighborhoodPost>> spec =
                ArgumentCaptor.forClass(Specification.class);
        verify(postRepository).findAll(spec.capture(), any(Pageable.class));

        spec.getValue().toPredicate(root, null, cb);

        // The reader's explicit follow choice rides VISIBLE posts only —
        // and the IN carries exactly the followed authors.
        verify(cb).equal(statusPath, PostStatus.VISIBLE);
        verify(authorPath).in(authors);
    }
}
