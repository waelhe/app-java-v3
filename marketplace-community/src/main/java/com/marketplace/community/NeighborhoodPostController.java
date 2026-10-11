package com.marketplace.community;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * L42 (neighborhood community plan §5 — the posts/feed/comments layer):
 * the neighborhood feed surface. Every endpoint sits behind the
 * resource-server chain's {@code anyRequest().authenticated()} and the
 * service's active-membership gate (403 — G-N3's default) — no
 * security-config change, the same zero-config line every layer since
 * L20 has ridden.
 *
 * <p><b>The two URL families</b> (the plan's own contract): the feed
 * pair under {@code /neighborhood/posts} (the member's own neighborhood
 * — the membership IS the scope, there is no location parameter to read
 * anyone else's feed) and the post-scoped pair under {@code /posts/{id}}
 * (comments, and the author's own delete).
 *
 * <p><b>The two write limiters</b> (the plan: "نمطلتان مسماتان
 * مستقلتان"): {@code postCreate} and {@code postComment}, both the L29
 * model — named Resilience4j instances, fail fast with 429 RL-001.
 *
 * <p><b>The category type gate</b> (criterion 3): the category arrives
 * as a String and parses through {@link #parseCategory(String)} BEFORE
 * any service call — an invalid value answers the house 400 with the
 * valid vocabulary listed, never an enum-binding 500.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class NeighborhoodPostController {

    /**
     * The post body's documented bound — the lead message's own limit
     * (V52's {@code VARCHAR(2000)}), the house's authored-message bound.
     * The column itself is TEXT, so a future widening is a product
     * decision that needs no migration — the bound lives at the type
     * gate, exactly where the plan's criterion 3 pins it.
     */
    static final int MAX_BODY_LENGTH = 2000;
    static final int MAX_SEARCH_QUERY_LENGTH = 200;

    private final NeighborhoodPostService postService;
    private final CurrentUserProvider currentUserProvider;

    public NeighborhoodPostController(NeighborhoodPostService postService,
                                       CurrentUserProvider currentUserProvider) {
        this.postService = postService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/neighborhood/posts")
    @Operation(summary = "Read my neighborhood's feed",
            description = "The caller's OWN neighborhood's VISIBLE posts, newest first — the "
                    + "membership is the scope (there is no location parameter: one membership, "
                    + "one feed — G-N1/G-N3). No active membership answers 403. The optional "
                    + "category filter is the feed's one axis (GENERAL/CLASSIFIED/LOST_FOUND/"
                    + "RECOMMENDATION/QUESTION/REQUEST — the category vocabulary); "
                    + "an invalid value answers 400 before any read. Deterministic pagination on "
                    + "the complete sort key (createdAt DESC, id DESC) — no shaky page boundaries.")
    public ResponseEntity<PagedResponse<NeighborhoodPostView>> feed(
            @Parameter(description = "Optional category filter — GENERAL, CLASSIFIED, LOST_FOUND, RECOMMENDATION, QUESTION or REQUEST")
            @RequestParam(required = false) String category,
            Pageable pageable,
            Authentication authentication) {
        UUID callerId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(
                postService.getFeed(callerId, parseCategory(category), pageable)));
    }

    @GetMapping("/neighborhood/posts/search")
    @Operation(summary = "Search visible posts in my neighborhood",
            description = "Full-text search is scoped to the caller's active neighborhood, visible posts only. "
                    + "The search uses PostgreSQL's Arabic text-search configuration and ranks by relevance; "
                    + "a typo-tolerant pg_trgm fallback runs only when full-text search has no matches. "
                    + "Pagination is deterministic. The category filter is optional.")
    public ResponseEntity<PagedResponse<NeighborhoodPostView>> search(
            @Parameter(description = "Text to search, trimmed and limited to 200 Unicode code points")
            @RequestParam("q") String query,
            @Parameter(description = "Optional category filter — GENERAL, CLASSIFIED, LOST_FOUND, RECOMMENDATION, QUESTION or REQUEST")
            @RequestParam(required = false) String category,
            Pageable pageable,
            Authentication authentication) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) {
            throw new BadRequestException("q must not be blank");
        }
        if (normalizedQuery.codePointCount(0, normalizedQuery.length()) > MAX_SEARCH_QUERY_LENGTH) {
            throw new BadRequestException("q must not exceed " + MAX_SEARCH_QUERY_LENGTH + " Unicode code points");
        }
        UUID callerId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(
                postService.searchFeed(callerId, normalizedQuery, parseCategory(category), pageable)));
    }

    @PostMapping("/neighborhood/posts")
    @RateLimiter(name = "postCreate")
    @Operation(summary = "Publish a post in my neighborhood",
            description = "Writes a post into the caller's active neighborhood. The gate order "
                    + "(before any write): the location resolves through the geo port (404 "
                    + "unknown), must be a level-3 neighborhood node (400 otherwise), and the "
                    + "caller must hold an active membership in exactly that location (403 "
                    + "otherwise). Title is bounded at 200 characters, body at 2000 — the type "
                    + "gate answers 400 before any write. The post is VISIBLE from creation; "
                    + "hiding is the moderation layer's flip alone.")
    public ResponseEntity<NeighborhoodPostView> create(
            @Valid @RequestBody CreatePostRequest request,
            Authentication authentication) {
        UUID authorId = currentUserProvider.getCurrentUserId(authentication);
        NeighborhoodPostView view = postService.createPost(
                authorId,
                request.locationId(),
                parseCategory(request.category()),
                request.title(),
                request.body());
        return ResponseEntity.status(201).body(view);
    }

    @GetMapping("/posts/{postId}/comments")
    @Operation(summary = "Read one post's comments",
            description = "One VISIBLE post's comments, chronological on the complete sort key "
                    + "(createdAt ASC, id ASC). Reached through the post gate first: an unknown, "
                    + "hidden or deleted post answers the honest 404 — a hidden post's comments "
                    + "are absent exactly as the post itself is. The membership gate matches the "
                    + "feed's: an active membership in the post's own neighborhood, else 403.")
    public ResponseEntity<PagedResponse<PostCommentView>> comments(
            @PathVariable UUID postId,
            Pageable pageable,
            Authentication authentication) {
        UUID callerId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(
                postService.getComments(callerId, postId, pageable)));
    }

    @PostMapping("/posts/{postId}/comments")
    @RateLimiter(name = "postComment")
    @Operation(summary = "Comment on a post",
            description = "Writes a comment on a VISIBLE post — the same active-membership gate "
                    + "as the feed, in the post's OWN neighborhood (403 otherwise; 404 when the "
                    + "post is unknown, hidden or deleted). The comment body is bounded at 2000 "
                    + "characters. The post's author is notified (POST_COMMENTED) after commit — "
                    + "unless the commenter IS the author.")
    public ResponseEntity<PostCommentView> comment(
            @PathVariable UUID postId,
            @Valid @RequestBody CreateCommentRequest request,
            Authentication authentication) {
        UUID commenterId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.status(201).body(
                postService.comment(commenterId, postId, request.body()));
    }

    @DeleteMapping("/posts/{postId}")
    @Operation(summary = "Delete my post",
            description = "The author's own soft delete: the row stays (the audit trail keeps "
                    + "every revision — b-5's retention), the reads stop returning it, and the "
                    + "comments follow in the read path. Only the author — anyone else answers "
                    + "403; an unknown post answers 404.")
    public ResponseEntity<Void> delete(
            @PathVariable UUID postId,
            Authentication authentication) {
        UUID authorId = currentUserProvider.getCurrentUserId(authentication);
        postService.deleteByAuthor(authorId, postId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{postId}/lost-found-state")
    @Operation(summary = "Close my lost-and-found report",
            description = "JT-20 — the lost-and-found lifecycle's owner command: the author of a "
                    + "LOST_FOUND post moves its state to RESOLVED (closed by other means) or "
                    + "FOUND (the item or being was recovered). The gate order is the delete's "
                    + "ownership gate verbatim: an unknown post answers 404, a non-owner 403, and "
                    + "a post that is not LOST_FOUND answers the honest 409 (it carries no "
                    + "lost-and-found state to move). A report already in the requested state "
                    + "answers 200 idempotently — no write. The response carries the state only "
                    + "for LOST_FOUND posts (other categories never render one). Note: this "
                    + "command rides no dedicated rate limiter — the named write limiters' "
                    + "config lives in the app module; adding one is a config decision, not a "
                    + "silent omission.")
    public ResponseEntity<NeighborhoodPostView> lostFoundState(
            @PathVariable UUID postId,
            @Valid @RequestBody UpdateLostFoundStateRequest request,
            Authentication authentication) {
        UUID ownerId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(
                postService.updateLostFoundState(ownerId, postId, parseLostFoundState(request.state())));
    }

    @PostMapping("/posts/{postId}/reactions")
    @RateLimiter(name = "postReact")
    @Operation(summary = "Thank a post (one voice per member)",
            description = "L47 — the feed's lightest write, Nextdoor's own first signature. "
                    + "The gate order is the comment's own verbatim: the post gate first "
                    + "(an unknown, hidden or deleted post answers the honest 404 — a hidden "
                    + "post's reactions are absent exactly as the post itself is), then the "
                    + "active-membership gate in the post's OWN neighborhood (403 otherwise — "
                    + "a reaction is a community contribution like a comment), and only then "
                    + "the one-voice check: a member who already holds a LIVE thank on this "
                    + "post answers 409 («صوت واحد لكل عضو» — the V64 report precedent; the "
                    + "V73 partial unique index is the backstop). The post's author is "
                    + "notified (POST_REACTED) after commit — unless the reactor IS the author. "
                    + "The feed read carries the live count and the caller's own voice "
                    + "(reactionsCount / reactedByMe).")
    public ResponseEntity<PostReactionView> react(
            @PathVariable UUID postId,
            Authentication authentication) {
        UUID memberId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.status(201).body(postService.react(memberId, postId));
    }

    @DeleteMapping("/posts/{postId}/reactions")
    @RateLimiter(name = "postReact")
    @Operation(summary = "Remove my thank from a post",
            description = "L47 — the un-thank. The gate order matches the thank (the post gate's "
                    + "honest 404, then the membership gate's 403); a member with no LIVE thank "
                    + "on the post answers the honest 404 (there is nothing to remove — the "
                    + "leave-neighborhood convention). The removal is the house soft delete: "
                    + "the row stays (b-5's retention, the Envers trail keeps the revision) and "
                    + "the voice is free for a fresh one. 204 on success.")
    public ResponseEntity<Void> removeReaction(
            @PathVariable UUID postId,
            Authentication authentication) {
        UUID memberId = currentUserProvider.getCurrentUserId(authentication);
        postService.removeReaction(memberId, postId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The category type gate (criterion 3): a String in, the enum out —
     * an invalid value answers the house 400 listing the valid
     * vocabulary, BEFORE any service call (and therefore before any
     * read or write).
     */
    private static PostCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PostCategory.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(
                    "Invalid category '" + raw + "' — valid values: GENERAL, CLASSIFIED, LOST_FOUND, RECOMMENDATION, QUESTION, REQUEST");
        }
    }

    /**
     * JT-20: the lost-and-found state type gate (the same discipline):
     * the request body's vocabulary is the CLOSING states only — ACTIVE
     * is the publish factory's own stamp, not a client command, so a
     * body asking for it answers the house 400 listing the valid
     * vocabulary, BEFORE any service call.
     */
    private static LostFoundState parseLostFoundState(String raw) {
        if ("RESOLVED".equals(raw) || "FOUND".equals(raw)) {
            return LostFoundState.valueOf(raw);
        }
        throw new BadRequestException(
                "Invalid lost-found state '" + raw + "' — valid values: RESOLVED, FOUND");
    }

    /**
     * The publish body: the target neighborhood (the author's own — the
     * service gates the match), the category, and the two authored-text
     * fields with their documented bounds.
     */
    public record CreatePostRequest(
            @NotNull
            @Schema(description = "The geo tree node id of the author's neighborhood — must be "
                    + "the caller's active membership location (a level-3 node).",
                    example = "11111111-1111-4111-8111-111111111104")
            UUID locationId,

            @NotBlank
            @Schema(description = "The post purpose: GENERAL, CLASSIFIED, LOST_FOUND, RECOMMENDATION, QUESTION, or REQUEST.",
                    allowableValues = {"GENERAL", "CLASSIFIED", "LOST_FOUND", "RECOMMENDATION", "QUESTION", "REQUEST"},
                    example = "GENERAL")
            String category,

            @NotBlank
            @Size(max = 200)
            @Schema(description = "The post's title (max 200 characters).", maxLength = 200,
                    example = "Missing cat near the old market")
            String title,

            @NotBlank
            @Size(max = MAX_BODY_LENGTH)
            @Schema(description = "The post's body (max " + MAX_BODY_LENGTH + " characters).",
                    maxLength = MAX_BODY_LENGTH)
            String body
    ) {
    }

    /** The comment body: one authored-text field with its documented bound. */
    public record CreateCommentRequest(
            @NotBlank
            @Size(max = MAX_BODY_LENGTH)
            @Schema(description = "The comment's body (max " + MAX_BODY_LENGTH + " characters).",
                    maxLength = MAX_BODY_LENGTH)
            String body
    ) {
    }

    /**
     * JT-20: the lost-and-found closing body — the one authored decision
     * the owner makes about their report: how the story ended.
     */
    public record UpdateLostFoundStateRequest(
            @NotBlank
            @Schema(description = "The report's closing state: RESOLVED (closed by other means) "
                    + "or FOUND (the lost item or being was recovered).",
                    allowableValues = {"RESOLVED", "FOUND"},
                    example = "FOUND")
            String state
    ) {
    }
}
