package com.marketplace.community;

import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.MediaLookupPort;
import com.marketplace.shared.api.PostCommentedEvent;
import com.marketplace.shared.api.PostReactedEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.BadRequestException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The neighborhood feed surface (neighborhood community plan §5-L42).
 * One gate order, three commands, one read pair — all measured from the
 * house precedents:
 *
 * <p><b>The read/write gate (G-N3's default, the plan's own words):</b>
 * the feed is for ACTIVE members of the neighborhood — an authenticated
 * caller with no active membership answers an explicit 403
 * ({@link AccessDeniedException} → the shared handler's ProblemDetail),
 * never an empty 200 that pretends the feed exists. The same gate guards
 * the comment write in the post's OWN {@code locationId} ("التعليق ليس
 * استثناءً: مساهمة مجتمعية كالمنشور" — the plan's CodeRabbit round-1
 * adoption on the plan itself).
 *
 * <p><b>The publish gate order (L41's own discipline, verbatim):</b> the
 * location is resolved through {@link GeoLookupPort} FIRST — an unknown
 * node is the port's own 404 — then the level-3 requirement answers 400
 * BEFORE any write, and only then does the active-membership match (403)
 * open the insert.
 *
 * <p><b>The comment's publication registry fact (the Modulith basis):</b>
 * {@link PostCommentedEvent} is published INSIDE the commenter's
 * transaction — the registry entry commits atomically with the comment
 * row, and the notifications listener runs AFTER_COMMIT in its own
 * REQUIRES_NEW unit ("the log entry stays untouched so that retry
 * mechanisms can be deployed"). The self-comment skip is the listener's
 * own policy; the publisher always publishes the honest fact.
 *
 * <p><b>Deterministic pagination (D-N5):</b> both reads force the
 * complete sort key — {@code created_at DESC, id DESC} down the feed,
 * {@code created_at ASC, id ASC} up the comment thread — so two rows in
 * the same second never shake a page boundary (the L32 lesson).
 */
@Service
@Transactional
public class NeighborhoodPostService {

    /**
     * The geo port's own level contract (GeoNode's javadoc): 3 =
     * neighborhood. The same constant the membership service gates on —
     * one vocabulary, the port's int.
     */
    static final int NEIGHBORHOOD_LEVEL = 3;

    /** The feed's complete sort key (D-N5) — newest first, id breaking ties. */
    private static final Sort FEED_SORT =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    /** The comment thread's complete sort key — chronological, id breaking ties. */
    private static final Sort COMMENT_SORT =
            Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));

    private final NeighborhoodPostRepository repository;
    private final PostCommentRepository commentRepository;
    private final PostReactionRepository reactionRepository;
    private final NeighborhoodMembershipRepository membershipRepository;
    private final GeoLookupPort geoLookupPort;
    private final MediaLookupPort mediaLookupPort;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public NeighborhoodPostService(NeighborhoodPostRepository repository,
                                   PostCommentRepository commentRepository,
                                   PostReactionRepository reactionRepository,
                                   NeighborhoodMembershipRepository membershipRepository,
                                   GeoLookupPort geoLookupPort,
                                   MediaLookupPort mediaLookupPort,
                                   ApplicationEventPublisher eventPublisher,
                                   Clock clock) {
        this.repository = repository;
        this.commentRepository = commentRepository;
        this.reactionRepository = reactionRepository;
        this.membershipRepository = membershipRepository;
        this.geoLookupPort = geoLookupPort;
        this.mediaLookupPort = mediaLookupPort;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Publish a post — the caller writes into their own active
     * neighborhood. The gate order is L41's own: port resolve (404) →
     * level-3 (400) → active membership in exactly that location (403) →
     * insert. A member of a DIFFERENT neighborhood answering this
     * neighborhood's id is the same 403 — G-N1's one-membership default
     * means the feed you read is the feed you write.
     */
    @Observed(name = "community.post.create")
    public NeighborhoodPostView createPost(UUID authorId, UUID locationId,
                                           PostCategory category, String title, String body) {
        GeoLookupPort.GeoNode node = geoLookupPort.getLocation(locationId);
        if (node.level() != NEIGHBORHOOD_LEVEL) {
            throw new BadRequestException(
                    "locationId must reference a level-3 neighborhood node, got level "
                            + node.level() + " (" + node.slug() + ")");
        }
        requireActiveMembershipIn(authorId, locationId,
                "Join a neighborhood before posting (PUT /api/v1/me/neighborhood)",
                "Posts go to your own neighborhood — this location is not it");
        NeighborhoodPost saved = repository.save(
                NeighborhoodPost.post(authorId, locationId, category, title, body, clock));
        return NeighborhoodPostView.of(saved);
    }

    /**
     * The feed — the caller's OWN neighborhood, VISIBLE posts only, on
     * the complete sort key. The one filter axis is {@code category}
     * (absent = the whole feed). No active membership ⇒ the explicit 403
     * (G-N3's default) — there is no location parameter to read anyone
     * else's feed: the membership IS the scope.
     */
    @Transactional(readOnly = true)
    public Page<NeighborhoodPostView> getFeed(UUID callerId, PostCategory category, Pageable pageable) {
        UUID locationId = requireActiveMembership(callerId,
                "Join a neighborhood before reading its feed (PUT /api/v1/me/neighborhood)")
                .getLocationId();
        Pageable feedPageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), FEED_SORT);
        Page<NeighborhoodPost> page = repository.findAll(
                NeighborhoodPostSpecifications.hasLocation(locationId)
                        .and(NeighborhoodPostSpecifications.isVisible())
                        .and(NeighborhoodPostSpecifications.hasCategory(category)),
                feedPageable);
        // L47: the feed read carries the two reaction facts — the grouped
        // live count per post and the caller's own live voice (the filled
        // heart the client renders). One grouped aggregate + one IN read
        // over the page's ids — a closed feed costs neither (the empty
        // page short-circuits below).
        //
        // L48: the same read now carries each post's media — ONE grouped
        // port read over the same page's ids (the reactions pattern
        // verbatim: no per-post reads, the empty page costs nothing). The
        // entries arrive presigned by the media module; the view maps them
        // to the feed's own read model (PostMediaView — no storage facts
        // cross the boundary).
        List<NeighborhoodPost> posts = page.getContent();
        Map<UUID, Long> counts = reactionCounts(posts);
        Set<UUID> mine = myReactions(callerId, posts);
        Map<UUID, List<MediaLookupPort.PostMediaEntry>> media = postMedia(posts);
        return page.map(post -> NeighborhoodPostView.of(
                post,
                counts.getOrDefault(post.getId(), 0L),
                mine.contains(post.getId()),
                media.getOrDefault(post.getId(), List.of())));
    }

    /**
     * L48: the page's post media, grouped by post id — one port call over
     * the ids (the L47 grouped-aggregate pattern). The empty page
     * short-circuits to the empty map (a closed feed costs no read);
     * the mapping keeps the port's flat order (postId, position) honest.
     */
    private Map<UUID, List<MediaLookupPort.PostMediaEntry>> postMedia(List<NeighborhoodPost> posts) {
        if (posts.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = posts.stream().map(NeighborhoodPost::getId).toList();
        return mediaLookupPort.findUploadedByPostIds(ids).stream()
                .collect(Collectors.groupingBy(
                        MediaLookupPort.PostMediaEntry::postId,
                        Collectors.toList()));
    }

    /**
     * Comment on a VISIBLE post — the same active-membership gate, in the
     * post's OWN {@code locationId}. The event is the fact; the
     * notification policy (no self-notify) is the listener's.
     */
    @Observed(name = "community.post.comment")
    public PostCommentView comment(UUID commenterId, UUID postId, String body) {
        NeighborhoodPost post = visiblePost(postId);
        requireActiveMembershipIn(commenterId, post.getLocationId(),
                "Join a neighborhood before commenting (PUT /api/v1/me/neighborhood)",
                "Only members of the post's neighborhood can comment");
        PostComment saved = commentRepository.save(
                PostComment.comment(postId, commenterId, body));
        eventPublisher.publishEvent(
                new PostCommentedEvent(postId, commenterId, post.getAuthorId()));
        return PostCommentView.of(saved);
    }

    /**
     * One post's comments — reached through the post gate first: an
     * unknown, hidden or deleted post answers the honest 404, so a
     * hidden post's comments are absent exactly as the post itself is
     * (the plan's criterion 5). Then the same membership gate as the
     * feed, in the post's own neighborhood.
     */
    @Transactional(readOnly = true)
    public Page<PostCommentView> getComments(UUID callerId, UUID postId, Pageable pageable) {
        NeighborhoodPost post = visiblePost(postId);
        requireActiveMembershipIn(callerId, post.getLocationId(),
                "Join a neighborhood before reading comments (PUT /api/v1/me/neighborhood)",
                "Only members of the post's neighborhood can read its comments");
        Pageable commentPageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), COMMENT_SORT);
        return commentRepository.findByPostId(postId, commentPageable)
                .map(PostCommentView::of);
    }

    /**
     * The author's own delete — the house soft delete. The row stays
     * (b-5's retention — the Envers trail keeps every revision), the
     * reads stop returning it, and the comments follow in the read path
     * (the aggregate's own is_deleted semantics — no physical delete
     * anywhere in this house). Only the author: anyone else answers 403.
     */
    @Observed(name = "community.post.delete")
    public void deleteByAuthor(UUID authorId, UUID postId) {
        NeighborhoodPost post = repository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post", postId));
        if (!post.getAuthorId().equals(authorId)) {
            throw new AccessDeniedException("Only the post's author can delete it");
        }
        repository.delete(post);
    }

    /**
     * L47 (the Nextdoor-2026 completeness wave — gap #1): thank a VISIBLE
     * post — the feed's lightest write and Nextdoor's own first
     * signature. The gate order is the comment's own verbatim: the post
     * gate first (unknown, hidden or deleted ⇒ the honest 404 — a hidden
     * post's reactions are absent exactly as the post itself is), then
     * the active-membership gate in the post's OWN {@code locationId}
     * (403 — a reaction is a community contribution like a comment), and
     * only then the one-voice check: a member with a LIVE reaction on
     * this post answers 409 (the product's own «صوت واحد لكل عضو» — the
     * V64 report precedent: the explicit 409 first, the V73 partial
     * unique index the backstop).
     *
     * <p>The event is the fact (the {@code PostCommentedEvent} contract
     * verbatim): {@code PostReactedEvent} is published INSIDE the
     * reactor's transaction so the registry entry commits atomically with
     * the reaction row; the self-thank skip is the listener's own policy.
     */
    @Observed(name = "community.post.react")
    public PostReactionView react(UUID memberId, UUID postId) {
        NeighborhoodPost post = visiblePost(postId);
        requireActiveMembershipIn(memberId, post.getLocationId(),
                "Join a neighborhood before reacting (PUT /api/v1/me/neighborhood)",
                "Only members of the post's neighborhood can react to it");
        if (reactionRepository.findByPostIdAndMemberId(postId, memberId).isPresent()) {
            throw new ConflictException(
                    "One thank per member per post — remove yours before thanking again");
        }
        PostReaction saved = reactionRepository.save(
                PostReaction.reaction(postId, memberId));
        eventPublisher.publishEvent(
                new PostReactedEvent(postId, memberId, post.getAuthorId()));
        return PostReactionView.of(saved);
    }

    /**
     * L47: un-thank — remove the caller's own LIVE reaction. The gate
     * order matches {@link #react(UUID, UUID)} (the post gate's honest
     * 404, then the membership gate's 403), and a member with no live
     * reaction on the post answers the honest 404 (the
     * leave-neighborhood convention: there is nothing to remove). The
     * un-thank is the house soft delete — the row stays (b-5's retention,
     * the Envers trail keeps the revision) and the voice is free for a
     * fresh one (the V73 partial unique index admits exactly that).
     */
    @Observed(name = "community.post.unreact")
    public void removeReaction(UUID memberId, UUID postId) {
        NeighborhoodPost post = visiblePost(postId);
        requireActiveMembershipIn(memberId, post.getLocationId(),
                "Join a neighborhood before reacting (PUT /api/v1/me/neighborhood)",
                "Only members of the post's neighborhood can react to it");
        PostReaction reaction = reactionRepository.findByPostIdAndMemberId(postId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Reaction", postId));
        reactionRepository.delete(reaction);
    }

    /**
     * The VISIBLE-post gate: soft-deleted rows are already filtered by
     * the entity's {@code @SoftDelete}; a HIDDEN_BY_MODERATOR post is
     * absent from the reads by the same honest-404 convention as an
     * unknown id — the feed and the comment surface share this one gate.
     */
    private NeighborhoodPost visiblePost(UUID postId) {
        return repository.findById(postId)
                .filter(post -> post.getStatus() == PostStatus.VISIBLE)
                .orElseThrow(() -> new ResourceNotFoundException("Post", postId));
    }

    /**
     * L47: the grouped live count per post over the page's ids — the
     * empty page short-circuits to the empty map (a closed feed costs
     * no aggregate).
     */
    private Map<UUID, Long> reactionCounts(List<NeighborhoodPost> posts) {
        if (posts.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = posts.stream().map(NeighborhoodPost::getId).toList();
        return reactionRepository.countByPostIdIn(ids).stream()
                .collect(Collectors.toMap(
                        PostReactionRepository.PostReactionCount::getPostId,
                        PostReactionRepository.PostReactionCount::getTotalCount));
    }

    /**
     * L47: the caller's own live reaction ids across the page's posts —
     * the filled-heart projection's one per-reader fact.
     */
    private Set<UUID> myReactions(UUID callerId, List<NeighborhoodPost> posts) {
        if (posts.isEmpty()) {
            return Set.of();
        }
        List<UUID> ids = posts.stream().map(NeighborhoodPost::getId).toList();
        return reactionRepository.findByMemberIdAndPostIdIn(callerId, ids).stream()
                .map(PostReaction::getPostId)
                .collect(Collectors.toSet());
    }

    /** The caller's ACTIVE membership, or the explicit 403 (G-N3). */
    private NeighborhoodMembership requireActiveMembership(UUID callerId, String noMembershipMessage) {
        NeighborhoodMembership membership = membershipRepository.findByUserId(callerId)
                .orElseThrow(() -> new AccessDeniedException(noMembershipMessage));
        if (!membership.mayUseCommunityWrites()) {
            throw new AccessDeniedException("Rejected neighborhood verification cannot publish, comment, or recommend");
        }
        return membership;
    }

    /**
     * The active-membership-in-location gate: absent membership ⇒ 403
     * with the join hint; a membership in a DIFFERENT neighborhood ⇒ 403
     * with the scope fact — both before any write.
     */
    private NeighborhoodMembership requireActiveMembershipIn(UUID callerId, UUID locationId,
                                                             String noMembershipMessage,
                                                             String wrongLocationMessage) {
        NeighborhoodMembership membership =
                requireActiveMembership(callerId, noMembershipMessage);
        if (!membership.getLocationId().equals(locationId)) {
            throw new AccessDeniedException(wrongLocationMessage);
        }
        return membership;
    }
}
