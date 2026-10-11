package com.marketplace.community;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.MediaLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JT-20 — the lost-and-found lifecycle, unit-pinned on the module's own
 * service-test shape (the {@code NeighborhoodPostServiceTest} precedent;
 * the integration test proves the schema side against the real
 * database):
 *
 * <ul>
 *   <li>the publish factory stamps ACTIVE on a LOST_FOUND report and
 *       NOTHING on the five other categories (the V171 column's null
 *       shape);</li>
 *   <li>the owner's close command: the delete gate's ownership order
 *       verbatim — unknown post 404, non-owner 403 with no write, a
 *       non-LOST_FOUND post 409 (it carries no state to move), and the
 *       flip itself rides the entity's package-private apply;</li>
 *   <li>the idempotent same-state request answers with the view and NO
 *       write;</li>
 *   <li>the view projection carries the state ONLY for LOST_FOUND.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class LostFoundLifecycleTest {

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
    private UUID otherMemberId = UUID.randomUUID();
    private UUID locationId = UUID.randomUUID();
    private UUID postId = UUID.randomUUID();

    private GeoLookupPort.GeoNode node(int level) {
        return new GeoLookupPort.GeoNode(locationId, null, level, "حي", null, "node");
    }

    private NeighborhoodMembership membershipOf(UUID user, UUID location) {
        return NeighborhoodMembership.join(user, location, clock);
    }

    private NeighborhoodPost lostReport() {
        return NeighborhoodPost.post(authorId, locationId,
                PostCategory.LOST_FOUND, "Missing cat near the old market",
                "Gray, answers to سوشي.", clock);
    }

    // ---------- publish: the birth state ----------

    @Test
    void publish_lostFoundReport_isBornActive() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        NeighborhoodPost stored = lostReport();
        when(repository.save(any())).thenReturn(stored);

        var view = service.createPost(authorId, locationId,
                PostCategory.LOST_FOUND, "Missing cat near the old market", "Gray.");

        // The lifecycle starts the moment the report is published.
        assertThat(view.category()).isEqualTo("LOST_FOUND");
        assertThat(view.lostFoundState()).isEqualTo("ACTIVE");
        assertThat(stored.getLostFoundState()).isEqualTo(LostFoundState.ACTIVE);
    }

    @Test
    void publish_anyOtherCategory_carriesNoState() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        NeighborhoodPost stored = NeighborhoodPost.post(authorId, locationId,
                PostCategory.GENERAL, "Title", "Body", clock);
        when(repository.save(any())).thenReturn(stored);

        var view = service.createPost(authorId, locationId,
                PostCategory.GENERAL, "Title", "Body");

        // The five non-LOST_FOUND categories carry NULL — a state on a
        // GENERAL post would be a fact the post does not have.
        assertThat(view.category()).isEqualTo("GENERAL");
        assertThat(view.lostFoundState()).isNull();
    }

    // ---------- the owner's close command ----------

    @Test
    void updateLostFoundState_unknownPost_is404() {
        when(repository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateLostFoundState(
                authorId, postId, LostFoundState.RESOLVED))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateLostFoundState_nonOwner_is403_noWrite() {
        when(repository.findById(postId)).thenReturn(Optional.of(lostReport()));

        assertThatThrownBy(() -> service.updateLostFoundState(
                otherMemberId, postId, LostFoundState.FOUND))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Only the post's author");
        verify(repository, never()).save(any());
    }

    @Test
    void updateLostFoundState_nonLostFoundPost_is409() {
        NeighborhoodPost general = NeighborhoodPost.post(authorId, locationId,
                PostCategory.QUESTION, "Who fixes boilers?", "Recommendations?", clock);
        when(repository.findById(postId)).thenReturn(Optional.of(general));

        // The honest conflict: the operation fights what the post IS —
        // a QUESTION carries no lost-and-found state to move.
        assertThatThrownBy(() -> service.updateLostFoundState(
                authorId, postId, LostFoundState.FOUND))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("LOST_FOUND");
        verify(repository, never()).save(any());
    }

    @Test
    void updateLostFoundState_ownerResolves_flipsAndSaves() {
        NeighborhoodPost report = lostReport();
        when(repository.findById(postId)).thenReturn(Optional.of(report));
        when(repository.save(any())).thenReturn(report);

        var view = service.updateLostFoundState(authorId, postId, LostFoundState.FOUND);

        assertThat(view.lostFoundState()).isEqualTo("FOUND");
        assertThat(report.getLostFoundState()).isEqualTo(LostFoundState.FOUND);
        verify(repository).save(report);
    }

    @Test
    void updateLostFoundState_sameState_isIdempotent_noWrite() {
        NeighborhoodPost report = lostReport();
        report.applyLostFoundState(LostFoundState.RESOLVED);
        when(repository.findById(postId)).thenReturn(Optional.of(report));

        var view = service.updateLostFoundState(authorId, postId, LostFoundState.RESOLVED);

        // The retried request lands on the same fact — answered
        // honestly, with no second write anywhere.
        assertThat(view.lostFoundState()).isEqualTo("RESOLVED");
        verify(repository, never()).save(any());
    }

    // ---------- the projection rule ----------

    @Test
    void view_carriesTheStateOnlyForLostFoundPosts() {
        var lost = NeighborhoodPostView.of(lostReport());
        assertThat(lost.lostFoundState()).isEqualTo("ACTIVE");

        var general = NeighborhoodPostView.of(NeighborhoodPost.post(authorId,
                locationId, PostCategory.RECOMMENDATION, "Plumber?", "Trustworthy", clock));
        assertThat(general.lostFoundState()).isNull();
    }
}
