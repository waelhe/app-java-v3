package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.MediaLookupPort;
import com.marketplace.shared.api.PostCommentedEvent;
import com.marketplace.shared.api.PostReactedEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L42 — the feed service's gate orders, unit-pinned (the integration
 * test proves both against the real schema):
 *
 * <ul>
 *   <li>the publish gate order: geo resolve (the port's own 404) →
 *       level-3 (400) → active membership in exactly that location
 *       (403 — absent OR a different neighborhood) → insert;</li>
 *   <li>the feed/comment read gates: no active membership ⇒ 403; a
 *       membership in a different neighborhood ⇒ 403;</li>
 *   <li>the comment's post gate: unknown, hidden or deleted ⇒ the honest
 *       404 BEFORE the membership gate consumes anything;</li>
 *   <li>the event is published INSIDE the command — the registry entry
 *       commits atomically with the comment row (the Modulith basis);</li>
 *   <li>the author delete: only the author (403 otherwise), soft, never
 *       physical.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NeighborhoodPostServiceTest {

    private static final Instant FIXED = Instant.parse("2026-09-17T09:30:00Z");

    @Mock
    private NeighborhoodPostRepository repository;

    @Mock
    private PostCommentRepository commentRepository;

    @Mock
    private PostReactionRepository reactionRepository;

    @Mock
    private NeighborhoodMembershipRepository membershipRepository;

    @Mock
    private GeoLookupPort geoLookupPort;

    @Mock
    private MediaLookupPort mediaLookupPort;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

    private NeighborhoodPostService service;

    @BeforeEach
    void setUp() {
        service = new NeighborhoodPostService(repository, commentRepository,
                reactionRepository, membershipRepository, geoLookupPort, mediaLookupPort,
                eventPublisher, clock);
    }

    private UUID authorId = UUID.randomUUID();
    private UUID commenterId = UUID.randomUUID();
    private UUID locationId = UUID.randomUUID();
    private UUID postId = UUID.randomUUID();

    private GeoLookupPort.GeoNode node(int level) {
        return new GeoLookupPort.GeoNode(locationId, null, level, "حي", null, "node");
    }

    private NeighborhoodMembership membershipOf(UUID user, UUID location) {
        return NeighborhoodMembership.join(user, location, clock);
    }

    private NeighborhoodPost visiblePost(UUID author, UUID location) {
        return NeighborhoodPost.post(author, location,
                PostCategory.GENERAL, "Title", "Body", false, clock);
    }

    // ---------- createPost ----------

    @Test
    void createPost_unknownLocation_isThePortsOwn404() {
        when(geoLookupPort.getLocation(locationId))
                .thenThrow(new ResourceNotFoundException("Location", locationId));

        assertThatThrownBy(() -> service.createPost(authorId, locationId,
                PostCategory.GENERAL, "Title", "Body", false))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createPost_nonLevel3Node_is400BeforeAnyWrite() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(0));

        assertThatThrownBy(() -> service.createPost(authorId, locationId,
                PostCategory.GENERAL, "Title", "Body", false))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("level-3");
        verify(membershipRepository, never()).findByUserId(authorId);
        verify(repository, never()).save(any());

        when(geoLookupPort.getLocation(locationId)).thenReturn(node(2));
        assertThatThrownBy(() -> service.createPost(authorId, locationId,
                PostCategory.GENERAL, "Title", "Body", false))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createPost_noMembership_is403BeforeAnyWrite() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPost(authorId, locationId,
                PostCategory.GENERAL, "Title", "Body", false))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Join a neighborhood");
        verify(repository, never()).save(any());
    }

    @Test
    void createPost_membershipInADifferentNeighborhood_is403() {
        UUID otherLocation = UUID.randomUUID();
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, otherLocation)));

        assertThatThrownBy(() -> service.createPost(authorId, locationId,
                PostCategory.GENERAL, "Title", "Body", false))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("own neighborhood");
        verify(repository, never()).save(any());
    }

    @Test
    void createPost_memberOfTheExactLocation_savesAndReturnsTheView() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        NeighborhoodPost stored = visiblePost(authorId, locationId);
        when(repository.save(any())).thenReturn(stored);

        var view = service.createPost(authorId, locationId,
                PostCategory.GENERAL, "Title", "Body", false);

        assertThat(view.authorId()).isEqualTo(authorId);
        assertThat(view.locationId()).isEqualTo(locationId);
        assertThat(view.status()).isEqualTo("VISIBLE");
        // ADR-0011 (DSA Art. 26(2)): the undeclared post reads undeclared.
        assertThat(view.declaredCommercial()).isFalse();
    }

    @Test
    void createPost_declaredCommercial_ridesThePostOnEveryRead() {
        // ADR-0011 (D-15 — DSA Art. 26(2)): the author's own declaration
        // is the stored fact the view carries — other recipients identify
        // the declared content in real time from the same read.
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        NeighborhoodPost stored = NeighborhoodPost.post(authorId, locationId,
                PostCategory.CLASSIFIED, "Selling my bike", "Barely used, message me.",
                true, clock);
        when(repository.save(any())).thenReturn(stored);

        var view = service.createPost(authorId, locationId,
                PostCategory.CLASSIFIED, "Selling my bike", "Barely used, message me.", true);

        assertThat(view.declaredCommercial()).isTrue();
    }

    // ---------- getFeed ----------

    @Test
    void getFeed_noMembership_is403() {
        when(membershipRepository.findByUserId(authorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFeed(authorId, null, PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeed_member_readsOwnNeighborhoodOnTheCompleteSortKey() {
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        Page<NeighborhoodPost> empty = Page.empty();
        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class))).thenReturn(empty);

        // An attacker-supplied sort must NOT reach the query — the service
        // forces the complete sort key (D-N5).
        Pageable attackerSorted = PageRequest.of(0, 20,
                Sort.by(Sort.Direction.ASC, "title"));
        service.getFeed(authorId, PostCategory.GENERAL, attackerSorted);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(
                any(org.springframework.data.jpa.domain.Specification.class),
                pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getValue().getSort().getOrderFor("id").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    // ---------- comment ----------

    @Test
    void comment_unknownPost_is404() {
        when(repository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.comment(commenterId, postId, "Nice"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(membershipRepository, never()).findByUserId(commenterId);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void comment_hiddenPost_is404() {
        NeighborhoodPost hidden = NeighborhoodPost.post(authorId, locationId,
                PostCategory.GENERAL, "Title", "Body", false, clock);
        // The only writer of HIDDEN_BY_MODERATOR is L45's moderation flip —
        // the entity exposes no setter because this layer never writes it.
        // The unit pin: simulate the stored row's state through reflection,
        // the honest channel for "a value this layer can never produce".
        org.springframework.test.util.ReflectionTestUtils.setField(
                hidden, "status", PostStatus.HIDDEN_BY_MODERATOR);
        when(repository.findById(postId)).thenReturn(Optional.of(hidden));

        assertThatThrownBy(() -> service.comment(commenterId, postId, "Nice"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(commentRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void comment_noMembership_is403BeforeAnyWrite() {
        when(repository.findById(postId))
                .thenReturn(Optional.of(visiblePost(authorId, locationId)));
        when(membershipRepository.findByUserId(commenterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.comment(commenterId, postId, "Nice"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Join a neighborhood");
        verify(commentRepository, never()).save(any());
    }

    @Test
    void comment_membershipInADifferentNeighborhood_is403() {
        when(repository.findById(postId))
                .thenReturn(Optional.of(visiblePost(authorId, locationId)));
        when(membershipRepository.findByUserId(commenterId))
                .thenReturn(Optional.of(membershipOf(commenterId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.comment(commenterId, postId, "Nice"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("post's neighborhood");
        verify(commentRepository, never()).save(any());
    }

    @Test
    void comment_memberOfThePostsNeighborhood_savesAndPublishesTheEvent() {
        NeighborhoodPost post = visiblePost(authorId, locationId);
        when(repository.findById(postId)).thenReturn(Optional.of(post));
        when(membershipRepository.findByUserId(commenterId))
                .thenReturn(Optional.of(membershipOf(commenterId, locationId)));
        PostComment stored = PostComment.comment(postId, commenterId, "Nice");
        when(commentRepository.save(any())).thenReturn(stored);

        var view = service.comment(commenterId, postId, "Nice");

        assertThat(view.postId()).isEqualTo(postId);
        // The registry fact: published INSIDE the command, carrying BOTH
        // ids — the self-comment skip is the listener's policy, not the
        // publisher's.
        ArgumentCaptor<PostCommentedEvent> event =
                ArgumentCaptor.forClass(PostCommentedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().postId()).isEqualTo(postId);
        assertThat(event.getValue().commentAuthorId()).isEqualTo(commenterId);
        assertThat(event.getValue().postAuthorId()).isEqualTo(authorId);
    }

    // ---------- getComments ----------

    @Test
    void getComments_unknownPost_is404() {
        when(repository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getComments(commenterId, postId, PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(commentRepository, never()).findByPostId(any(UUID.class), any(Pageable.class));
    }

    @Test
    void getComments_hiddenPost_is404() {
        NeighborhoodPost hidden = visiblePost(authorId, locationId);
        org.springframework.test.util.ReflectionTestUtils.setField(
                hidden, "status", PostStatus.HIDDEN_BY_MODERATOR);
        when(repository.findById(postId)).thenReturn(Optional.of(hidden));

        assertThatThrownBy(() -> service.getComments(commenterId, postId, PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(commentRepository, never()).findByPostId(any(UUID.class), any(Pageable.class));
    }

    @Test
    void getComments_noMembership_is403() {
        when(repository.findById(postId))
                .thenReturn(Optional.of(visiblePost(authorId, locationId)));
        when(membershipRepository.findByUserId(commenterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getComments(commenterId, postId, PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getComments_member_readsChronologicallyOnTheCompleteSortKey() {
        when(repository.findById(postId))
                .thenReturn(Optional.of(visiblePost(authorId, locationId)));
        when(membershipRepository.findByUserId(commenterId))
                .thenReturn(Optional.of(membershipOf(commenterId, locationId)));
        when(commentRepository.findByPostId(any(UUID.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.getComments(commenterId, postId, PageRequest.of(0, 20));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(commentRepository).findByPostId(any(UUID.class), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        assertThat(pageable.getValue().getSort().getOrderFor("id").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    // ---------- deleteByAuthor ----------

    @Test
    void deleteByAuthor_unknownPost_is404() {
        when(repository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteByAuthor(authorId, postId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteByAuthor_notTheAuthor_is403_noDelete() {
        when(repository.findById(postId))
                .thenReturn(Optional.of(visiblePost(UUID.randomUUID(), locationId)));

        assertThatThrownBy(() -> service.deleteByAuthor(authorId, postId))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).delete(any(NeighborhoodPost.class));
    }

    @Test
    void deleteByAuthor_theAuthor_softDeletes() {
        NeighborhoodPost post = visiblePost(authorId, locationId);
        when(repository.findById(postId)).thenReturn(Optional.of(post));

        service.deleteByAuthor(authorId, postId);

        // The house soft delete — never a physical remove anywhere.
        verify(repository).delete(post);
        verify(repository, never()).deleteById(any(UUID.class));
    }

    // ---------- L47: react / removeReaction / the feed's reaction facts ----------

    @Test
    void react_unknownPost_isTheHonest404() {
        when(repository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.react(commenterId, postId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reactionRepository, never()).save(any());
    }

    @Test
    void react_hiddenPost_is404_reactionsAbsentAsThePostItself() {
        NeighborhoodPost hidden = visiblePost(authorId, locationId);
        hideByModerator(hidden);
        when(repository.findById(postId)).thenReturn(Optional.of(hidden));

        assertThatThrownBy(() -> service.react(commenterId, postId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reactionRepository, never()).save(any());
    }

    @Test
    void react_noMembership_is403BeforeAnyWrite() {
        when(repository.findById(postId))
                .thenReturn(Optional.of(visiblePost(authorId, locationId)));
        when(membershipRepository.findByUserId(commenterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.react(commenterId, postId))
                .isInstanceOf(AccessDeniedException.class);
        verify(reactionRepository, never()).save(any());
    }

    @Test
    void react_membershipInADifferentNeighborhood_is403() {
        UUID otherLocation = UUID.randomUUID();
        when(repository.findById(postId))
                .thenReturn(Optional.of(visiblePost(authorId, locationId)));
        when(membershipRepository.findByUserId(commenterId))
                .thenReturn(Optional.of(membershipOf(commenterId, otherLocation)));

        assertThatThrownBy(() -> service.react(commenterId, postId))
                .isInstanceOf(AccessDeniedException.class);
        verify(reactionRepository, never()).save(any());
    }

    @Test
    void react_oneVoicePerMember_is409BeforeAnyWrite() {
        // The product's own «صوت واحد لكل عضو»: the explicit 409 first,
        // the V70 partial unique index the backstop.
        when(repository.findById(postId))
                .thenReturn(Optional.of(visiblePost(authorId, locationId)));
        when(membershipRepository.findByUserId(commenterId))
                .thenReturn(Optional.of(membershipOf(commenterId, locationId)));
        when(reactionRepository.findByPostIdAndMemberId(postId, commenterId))
                .thenReturn(Optional.of(PostReaction.reaction(postId, commenterId)));

        assertThatThrownBy(() -> service.react(commenterId, postId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("One thank per member");
        verify(reactionRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void react_memberOfThePostsNeighborhood_savesAndPublishesTheEvent() {
        NeighborhoodPost post = visiblePost(authorId, locationId);
        when(repository.findById(postId)).thenReturn(Optional.of(post));
        when(membershipRepository.findByUserId(commenterId))
                .thenReturn(Optional.of(membershipOf(commenterId, locationId)));
        when(reactionRepository.findByPostIdAndMemberId(postId, commenterId))
                .thenReturn(Optional.empty());
        when(reactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PostReactionView view = service.react(commenterId, postId);

        assertThat(view.postId()).isEqualTo(postId);
        assertThat(view.memberId()).isEqualTo(commenterId);
        // The event is the fact — published for EVERY reaction (the self-thank
        // skip is the listener's policy), carrying both ids.
        ArgumentCaptor<PostReactedEvent> event =
                ArgumentCaptor.forClass(PostReactedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().postId()).isEqualTo(postId);
        assertThat(event.getValue().reactorId()).isEqualTo(commenterId);
        assertThat(event.getValue().postAuthorId()).isEqualTo(authorId);
    }

    @Test
    void removeReaction_unknownPost_isTheHonest404() {
        when(repository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeReaction(commenterId, postId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reactionRepository, never()).delete(any(PostReaction.class));
    }

    @Test
    void removeReaction_noLiveVoice_isTheHonest404() {
        // The leave-neighborhood convention: there is nothing to remove.
        when(repository.findById(postId))
                .thenReturn(Optional.of(visiblePost(authorId, locationId)));
        when(membershipRepository.findByUserId(commenterId))
                .thenReturn(Optional.of(membershipOf(commenterId, locationId)));
        when(reactionRepository.findByPostIdAndMemberId(postId, commenterId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeReaction(commenterId, postId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reactionRepository, never()).delete(any(PostReaction.class));
    }

    @Test
    void removeReaction_membershipInADifferentNeighborhood_is403() {
        UUID otherLocation = UUID.randomUUID();
        when(repository.findById(postId))
                .thenReturn(Optional.of(visiblePost(authorId, locationId)));
        when(membershipRepository.findByUserId(commenterId))
                .thenReturn(Optional.of(membershipOf(commenterId, otherLocation)));

        assertThatThrownBy(() -> service.removeReaction(commenterId, postId))
                .isInstanceOf(AccessDeniedException.class);
        verify(reactionRepository, never()).delete(any(PostReaction.class));
    }

    @Test
    void removeReaction_theOwnersVoice_softDeletes_andFreesTheVoice() {
        when(repository.findById(postId))
                .thenReturn(Optional.of(visiblePost(authorId, locationId)));
        when(membershipRepository.findByUserId(commenterId))
                .thenReturn(Optional.of(membershipOf(commenterId, locationId)));
        PostReaction reaction = PostReaction.reaction(postId, commenterId);
        when(reactionRepository.findByPostIdAndMemberId(postId, commenterId))
                .thenReturn(Optional.of(reaction));

        service.removeReaction(commenterId, postId);

        // The house soft delete (never a physical remove) — and the un-thank
        // fires NO event: a removal carries no new fact for the author.
        verify(reactionRepository).delete(reaction);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeed_carriesTheTwoReactionFacts() {
        // The caller-scoped projection: the grouped live count per post and
        // the caller's own live voice — one IN read each over the page's ids.
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        NeighborhoodPost post = visiblePost(authorId, locationId);
        NeighborhoodPost strangerPost = visiblePost(UUID.randomUUID(), locationId);
        Page<NeighborhoodPost> page = new PageImpl<>(List.of(post, strangerPost));
        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class))).thenReturn(page);
        when(reactionRepository.countByPostIdIn(any())).thenReturn(List.of(
                countOf(post.getId(), 3L), countOf(strangerPost.getId(), 0L)));
        when(reactionRepository.findByMemberIdAndPostIdIn(eq(authorId), any()))
                .thenReturn(List.of(PostReaction.reaction(post.getId(), authorId)));
        when(mediaLookupPort.findUploadedByPostIds(any())).thenReturn(List.of(
                new MediaLookupPort.PostMediaEntry(post.getId(), UUID.randomUUID(),
                        "https://u", "https://t", "image/jpeg", 1)));

        Page<NeighborhoodPostView> views =
                service.getFeed(authorId, null, PageRequest.of(0, 20));

        NeighborhoodPostView mine = views.getContent().get(0);
        assertThat(mine.reactionsCount()).isEqualTo(3L);
        assertThat(mine.reactedByMe()).isTrue();
        NeighborhoodPostView strangers = views.getContent().get(1);
        assertThat(strangers.reactionsCount()).isZero();
        assertThat(strangers.reactedByMe()).isFalse();
    }

    /**
     * L48: the feed read carries each post's media — the one grouped port
     * read over the page's ids, mapped into the view's own read model; a
     * post with no entries rides the empty list. No storage fact (key,
     * status) crosses the boundary.
     */
    @Test
    @SuppressWarnings("unchecked")
    void getFeed_carriesThePostsMedia() {
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        NeighborhoodPost post = visiblePost(authorId, locationId);
        NeighborhoodPost strangerPost = visiblePost(UUID.randomUUID(), locationId);
        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(post, strangerPost)));
        when(reactionRepository.countByPostIdIn(any())).thenReturn(List.of());
        when(reactionRepository.findByMemberIdAndPostIdIn(any(), any())).thenReturn(List.of());
        UUID mediaId = UUID.randomUUID();
        when(mediaLookupPort.findUploadedByPostIds(any())).thenReturn(List.of(
                new MediaLookupPort.PostMediaEntry(post.getId(), mediaId,
                        "https://orig", "https://thumb", "image/jpeg", 1),
                new MediaLookupPort.PostMediaEntry(post.getId(), UUID.randomUUID(),
                        "https://orig2", null, "image/png", 2)));

        Page<NeighborhoodPostView> views =
                service.getFeed(authorId, null, PageRequest.of(0, 20));

        NeighborhoodPostView mine = views.getContent().get(0);
        assertThat(mine.media()).hasSize(2);
        assertThat(mine.media().get(0).mediaId()).isEqualTo(mediaId);
        assertThat(mine.media().get(0).url()).isEqualTo("https://orig");
        assertThat(mine.media().get(0).thumbUrl()).isEqualTo("https://thumb");
        assertThat(mine.media().get(0).contentType()).isEqualTo("image/jpeg");
        assertThat(mine.media().get(0).position()).isEqualTo(1);
        assertThat(mine.media().get(1).thumbUrl()).isNull();
        // the port's flat (postId, position) order survives the grouping
        assertThat(mine.media().get(1).position()).isEqualTo(2);
        // the post with no entries rides the empty list, honestly
        assertThat(views.getContent().get(1).media()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void getFeed_emptyPage_costsNoReactionRead() {
        // A closed feed costs neither aggregate nor voice read nor media
        // read — the short-circuit is the projection's own discipline.
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class))).thenReturn(Page.empty());

        service.getFeed(authorId, null, PageRequest.of(0, 20));

        verify(reactionRepository, never()).countByPostIdIn(any());
        verify(reactionRepository, never()).findByMemberIdAndPostIdIn(any(), any());
        verify(mediaLookupPort, never()).findUploadedByPostIds(any());
    }

    /** The grouped count's projection stub (the repository interface's own shape). */
    private PostReactionRepository.PostReactionCount countOf(UUID postId, long count) {
        return new PostReactionRepository.PostReactionCount() {
            @Override
            public UUID getPostId() {
                return postId;
            }

            @Override
            public long getTotalCount() {
                return count;
            }
        };
    }

    /** The moderation flip (package-private by design — the test rides it). */
    private void hideByModerator(NeighborhoodPost post) {
        post.hideByModerator();
    }
}
