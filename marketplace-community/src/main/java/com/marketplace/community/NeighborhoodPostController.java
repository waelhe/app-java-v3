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
                    + "category filter is the feed's one axis (GENERAL/CLASSIFIED/LOST_FOUND); "
                    + "an invalid value answers 400 before any read. Deterministic pagination on "
                    + "the complete sort key (createdAt DESC, id DESC) — no shaky page boundaries.")
    public ResponseEntity<PagedResponse<NeighborhoodPostView>> feed(
            @Parameter(description = "Optional category filter — GENERAL, CLASSIFIED or LOST_FOUND")
            @RequestParam(required = false) String category,
            Pageable pageable,
            Authentication authentication) {
        UUID callerId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(
                postService.getFeed(callerId, parseCategory(category), pageable)));
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
                    "Invalid category '" + raw + "' — valid values: GENERAL, CLASSIFIED, LOST_FOUND");
        }
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

            @NotNull
            @Schema(description = "The post's category.", allowableValues = {"GENERAL", "CLASSIFIED", "LOST_FOUND"},
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
}
