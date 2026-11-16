package com.marketplace.community;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * L51 (the Nextdoor-2026 completeness wave — gap #6, the neighbors
 * groups): the neighborhood groups surface «مجموعات الجيران». Every
 * endpoint sits behind the resource-server chain's
 * {@code anyRequest().authenticated()} and the service's
 * active-membership gate (403 — G-N3's default) — no security-config
 * change, the same zero-config line every layer since L20 has ridden.
 *
 * <p><b>The two URL families</b> (the posts/events/market controllers'
 * own contract shape): the board under {@code /neighborhood/groups}
 * (the member's own neighborhood — the membership IS the scope, there
 * is no location parameter to read anyone else's board) and the
 * group-scoped membership pair under
 * {@code /neighborhood/groups/{groupId}/membership} — the gap
 * analysis's own registered shape
 * ({@code POST /groups/{id}/membership}).
 *
 * <p><b>The one write limiter</b> (the plan's own «حدود معدل مسمّاة
 * محافظة» discipline): {@code groupMembership}, the L29 model — a
 * named Resilience4j instance, fail fast with 429 RL-001, at the
 * postReact/eventRsvp id-pair budget (one named instance for BOTH the
 * join and the leave — they are one toggle's two directions, the
 * postReact/eventRsvp model verbatim: a join/leave burst is one
 * member's one behavior, not two).
 *
 * <p><b>No type gates ride here</b> — and that is the measured shape:
 * the registered contract carries no enumerated vocabulary (no
 * category, no condition — the group is name/description/members), and
 * the membership pair's only parameter is the path's own UUID. There
 * is nothing to parse before the service call, so the house
 * parse-then-400 discipline has no work to do on this surface.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class NeighborhoodGroupController {

    private final NeighborhoodGroupService groupService;
    private final CurrentUserProvider currentUserProvider;

    public NeighborhoodGroupController(NeighborhoodGroupService groupService,
                                       CurrentUserProvider currentUserProvider) {
        this.groupService = groupService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/neighborhood/groups")
    @Operation(summary = "Read my neighborhood's groups board",
            description = "The caller's OWN neighborhood's specialist clubs, in the hood's "
                    + "historical order (oldest club first — the seed's own insertion order) on "
                    + "the complete sort key (createdAt ASC, id ASC). The membership is the "
                    + "scope (there is no location parameter: one membership, one board — "
                    + "G-N1/G-N3); no active membership answers 403. Every row carries the "
                    + "two reader-scoped facts: members (the LIVE membership count — one "
                    + "grouped read over the page, earned by real rows) and joinedByMe (the "
                    + "caller's own live membership — the join/leave button's honest gate). "
                    + "Deterministic pagination on the complete sort key — no shaky page "
                    + "boundaries.")
    public ResponseEntity<PagedResponse<NeighborhoodGroupView>> board(
            Pageable pageable,
            Authentication authentication) {
        UUID callerId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(groupService.getBoard(callerId, pageable)));
    }

    @PostMapping("/neighborhood/groups/{groupId}/membership")
    @RateLimiter(name = "groupMembership")
    @Operation(summary = "Join a group in my neighborhood",
            description = "Takes the caller's single live membership in one live group. The "
                    + "gate order (before any write): the group resolves (404 unknown or "
                    + "retired — a retired group's memberships are absent exactly as the "
                    + "group itself is), the caller must hold an active — and REJECTED-excluded "
                    + "— membership in exactly the group's own neighborhood (403 otherwise), "
                    + "and only ONE live membership per member per group exists (409 — "
                    + "«عضوية واحدة لكل جار»; the partial unique index is the backstop). "
                    + "The echo carries the fresh membership's own facts.")
    public ResponseEntity<NeighborhoodGroupMembershipView> join(
            @PathVariable
            @Parameter(description = "The group to join — a club of the caller's own neighborhood.",
                    example = "46464646-4646-4646-8646-464646460001")
            UUID groupId,
            Authentication authentication) {
        UUID memberId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.status(201).body(groupService.join(memberId, groupId));
    }

    @DeleteMapping("/neighborhood/groups/{groupId}/membership")
    @RateLimiter(name = "groupMembership")
    @Operation(summary = "Leave a group I joined",
            description = "Removes the caller's own LIVE membership. The same gates as the "
                    + "join (the group's honest 404, then the neighborhood-membership 403); "
                    + "a member with no live membership on the group answers the honest 404 "
                    + "(there is nothing to leave). 204 on success — the seat is free for a "
                    + "fresh join (the soft-deleted row stays for the audit trail).")
    public ResponseEntity<Void> leave(
            @PathVariable
            @Parameter(description = "The group to leave — a club the caller belongs to.",
                    example = "46464646-4646-4646-8646-464646460001")
            UUID groupId,
            Authentication authentication) {
        UUID memberId = currentUserProvider.getCurrentUserId(authentication);
        groupService.leave(memberId, groupId);
        return ResponseEntity.noContent().build();
    }
}
