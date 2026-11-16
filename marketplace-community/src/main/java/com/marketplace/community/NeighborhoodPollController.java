package com.marketplace.community;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * L52 (the Nextdoor-2026 completeness wave — gap #7, the polls): the
 * neighborhood polls surface «استطلاعات الرأي». Every endpoint sits
 * behind the resource-server chain's
 * {@code anyRequest().authenticated()} and the service's
 * active-membership gate (403 — G-N3's default) — no security-config
 * change, the same zero-config line every layer since L20 has ridden.
 *
 * <p><b>The two URL families</b> (the posts/events/market/groups
 * controllers' own contract shape): the board pair under
 * {@code /neighborhood/polls} (the member's own neighborhood — the
 * membership IS the scope, there is no location parameter to read
 * anyone else's board) and the poll-scoped vote pair under
 * {@code /polls/{pollId}/vote} — the gap analysis's own registered
 * shape ({@code POST /polls/{id}/vote}).
 *
 * <p><b>The two write limiters</b> (the plan's own «حدود معدل مسمّاة
 * محافظة» discipline): {@code pollCreate}, the L29 model at the
 * postCreate/eventCreate/marketCreate budget (a poll is the board's
 * unit, a full authored record like a post) and {@code pollVote} at
 * the postReact/eventRsvp/groupMembership id-pair budget (a vote is
 * the lightest id-pair write the platform owns — no authored text at
 * all). The withdraw rides no limiter of its own (the postDelete/
 * eventDelete/marketDelete stance verbatim: a member managing their
 * own rows is not an abuse surface worth a budget).
 *
 * <p><b>The type gates</b> (the posts'/events'/market's own
 * discipline): the label bounds arrive bean-side
 * ({@code @NotBlank @Size(max = 200)} — the market title's own
 * shape), and the option-set cardinality is the service's own 400
 * with the registered contract's own words («ONE question, 2–5
 * options») — it lands AFTER the L41 publish gate, exactly where the
 * market's ONE pricing rule and the events' registration rule sit.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class NeighborhoodPollController {

    /**
     * The label bounds — the house {@code provider_listings.title}
     * limit (V2's own documented bound): the question, the author
     * label and every option label are one-line display strings, the
     * post title's own shape.
     */
    static final int MAX_LABEL_LENGTH = 200;

    private final NeighborhoodPollService pollService;
    private final CurrentUserProvider currentUserProvider;

    public NeighborhoodPollController(NeighborhoodPollService pollService,
                                      CurrentUserProvider currentUserProvider) {
        this.pollService = pollService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/neighborhood/polls")
    @Operation(summary = "Read my neighborhood's polls board",
            description = "The caller's OWN neighborhood's polls, newest first on the "
                    + "complete sort key (createdAt DESC, id DESC — the featured zone "
                    + "carries the LATEST poll). The membership is the scope (there is "
                    + "no location parameter: one membership, one board — G-N1/G-N3); "
                    + "no active membership answers 403. Every row carries the full "
                    + "authored option set in the author's own order — each option with "
                    + "its LIVE vote count (earned by real rows) — and votedByMe (the "
                    + "caller's own chosen option id, null when not voted: the card "
                    + "renders its mine mark and the honest not-yet-voted state from "
                    + "the contract alone, no second read). Deterministic pagination — "
                    + "no shaky page boundaries.")
    public ResponseEntity<PagedResponse<NeighborhoodPollView>> board(
            Pageable pageable,
            Authentication authentication) {
        UUID callerId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(pollService.getBoard(callerId, pageable)));
    }

    @PostMapping("/neighborhood/polls")
    @RateLimiter(name = "pollCreate")
    @Operation(summary = "Author a poll in my neighborhood",
            description = "Writes a poll — ONE question with its full 2–5 option set — "
                    + "into the caller's active neighborhood, as ONE authored unit in "
                    + "one transaction. The gate order (before any write): the location "
                    + "resolves through the geo port (404 unknown), must be a level-3 "
                    + "neighborhood node (400 otherwise), the caller must hold an active "
                    + "— and REJECTED-excluded — membership in exactly that location "
                    + "(403 otherwise), and the option set must carry 2 to 5 options "
                    + "(400 otherwise — the registered contract's own words). The "
                    + "author's own submission order IS the options' stored display "
                    + "order. Question, authorLabel and every option label are bounded "
                    + "at 200 characters.")
    public ResponseEntity<NeighborhoodPollView> create(
            @Valid @RequestBody CreatePollRequest request,
            Authentication authentication) {
        UUID authorId = currentUserProvider.getCurrentUserId(authentication);
        NeighborhoodPollView view = pollService.create(
                authorId,
                request.locationId(),
                request.question(),
                request.authorLabel(),
                request.options());
        return ResponseEntity.status(201).body(view);
    }

    @PostMapping("/polls/{pollId}/vote")
    @RateLimiter(name = "pollVote")
    @Operation(summary = "Cast my vote on a poll",
            description = "Takes the caller's single live vote on one poll — the "
                    + "registered contract's own real write (صوت واحد لكل عضو). The "
                    + "gate order (before any write): the poll resolves (404 unknown or "
                    + "retired — a retired poll's options and votes are absent exactly "
                    + "as the poll itself is), the option resolves (404 unknown) and "
                    + "must belong to THIS poll (400 otherwise — a vote carries one of "
                    + "the poll's own options), the caller must hold an active — and "
                    + "REJECTED-excluded — membership in exactly the poll's own "
                    + "neighborhood (403 otherwise), and only ONE live vote per member "
                    + "per poll exists (409 — the partial unique index is the backstop). "
                    + "The echo carries the fresh vote's own stored facts.")
    public ResponseEntity<NeighborhoodPollVoteView> vote(
            @PathVariable
            @Parameter(description = "The poll to vote in — a poll of the caller's own neighborhood.",
                    example = "48484848-4848-4484-8484-484848480001")
            UUID pollId,
            @Valid @RequestBody VoteRequest request,
            Authentication authentication) {
        UUID memberId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.status(201).body(
                pollService.vote(memberId, pollId, request.optionId()));
    }

    @DeleteMapping("/polls/{pollId}/vote")
    @Operation(summary = "Withdraw my vote on a poll",
            description = "Removes the caller's own LIVE vote. The poll's existence gate "
                    + "answers the honest 404 (a retired poll's votes are absent exactly "
                    + "as the poll itself is), and the removal is owner-scoped (the /me "
                    + "owner-delete convention): a member with no live vote on the poll "
                    + "answers the honest 404 (there is nothing to withdraw), and the "
                    + "vote's neighborhood gate never rides the withdraw — a former "
                    + "neighbor takes his stale vote with him. 204 on success — the "
                    + "member is free to vote again (the soft-deleted row stays for the "
                    + "audit trail).")
    public ResponseEntity<Void> withdraw(
            @PathVariable
            @Parameter(description = "The poll whose vote to withdraw — one the caller voted in.",
                    example = "48484848-4848-4484-8484-484848480001")
            UUID pollId,
            Authentication authentication) {
        UUID memberId = currentUserProvider.getCurrentUserId(authentication);
        pollService.withdraw(memberId, pollId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The authoring body: the target neighborhood (the author's own —
     * the service gates the match), the ONE question, the committee/
     * role label the poll publishes under, and the full option set in
     * the author's own display order (2–5 one-line labels — the
     * cardinality gate is the service's own 400, the registered
     * contract's own words).
     */
    public record CreatePollRequest(
            @NotNull
            @Schema(description = "The geo tree node id of the author's neighborhood — must "
                    + "be the caller's active membership location (a level-3 node).",
                    example = "11111111-1111-4111-8111-111111111104")
            UUID locationId,

            @NotBlank
            @Size(max = MAX_LABEL_LENGTH)
            @Schema(description = "The poll's ONE question — its whole authored text "
                    + "(max 200 characters; the card carries no separate body).",
                    maxLength = MAX_LABEL_LENGTH,
                    example = "ما المواعيد الأنسب لفتح الممشى المظلل خلال الصيف؟")
            String question,

            @NotBlank
            @Size(max = MAX_LABEL_LENGTH)
            @Schema(description = "The committee/role label the poll publishes under — a "
                    + "display string, never a person identity (the design's own "
                    + "authorship shape: «لجنة تطوير الحي»).",
                    maxLength = MAX_LABEL_LENGTH,
                    example = "لجنة تطوير الحي")
            String authorLabel,

            @NotEmpty
            @Schema(description = "The full option set in the author's own display order — "
                    + "2 to 5 one-line labels (the cardinality gate is the service's own "
                    + "400 with the registered contract's own words).")
            List<@NotBlank @Size(max = MAX_LABEL_LENGTH) String> options
    ) {
    }

    /**
     * The voting body: the chosen option's id — one of the poll's own
     * options (the service's own gates: the honest 404 for an unknown
     * option, the 400 when it belongs to a different poll).
     */
    public record VoteRequest(
            @NotNull
            @Schema(description = "The chosen option's id — one of this poll's own options.",
                    example = "49494949-4949-4494-9494-494949490001")
            UUID optionId
    ) {
    }
}
